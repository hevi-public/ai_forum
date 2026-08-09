package com.aiforum.jailcontract

import com.aiforum.config.JailProperties
import com.aiforum.llm.ExecResult
import com.aiforum.llm.JailInvocation
import com.aiforum.llm.JailLauncher
import com.aiforum.llm.RealCommandRunner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import java.util.UUID

/**
 * The jail's **contract with Docker** — the half no unit test can reach. tier0/JailLauncherTest proves we
 * ASK for a read-only, capability-less container on a network with no route out; this proves Docker
 * actually delivers one. It builds the real image, stands up the real topology, and runs probes inside it
 * using argv from the real [JailLauncher.wrap] with only the trailing `claude …` swapped for `curl`/`sh`.
 *
 * **Opt-in: `./gradlew jailContract`, deliberately NOT part of `verifyAll`.** It needs a Docker daemon, a
 * few minutes of image build, and real internet — a gate that requires those is a gate that goes red for
 * reasons that have nothing to do with the code. Correspondingly it does NOT skip when Docker is missing:
 * you typed the task name, so silence would be the wrong answer.
 *
 * The load-bearing case is #2. It is a tripwire on the SHIPPED default allowlist
 * ([JailProperties.egressAllowlist]) — widen that default to admit `example.com` and case 2 goes red,
 * which is what makes its green mean "policy holds" rather than "curl happened to fail". Case 3 runs the
 * identical probe through a deliberately wider proxy and expects success, so a broken image or a dead
 * network can't masquerade as a working deny.
 */
@Tag("jailContract")
class JailContractTest {

    companion object {
        /** Unique per run, so this can be exercised beside a running app without touching its jail. */
        private val tag = UUID.randomUUID().toString().take(8)
        private val image = "aiforum-claude-jail-contract-$tag"
        private val network = "aiforum-jail-contract-net-$tag"
        private val strictProxy = "aiforum-jail-contract-strict-$tag"
        private val wideProxy = "aiforum-jail-contract-wide-$tag"

        /** The SHIPPED defaults — this is what makes case 2 a tripwire on production config. */
        private val shipped = JailProperties()

        /** maxWallClockSeconds is trimmed only so a wedged probe fails in a minute rather than fifteen. */
        private val strict = shipped.copy(
            image = image, network = network, proxyName = strictProxy, maxWallClockSeconds = 60,
        )
        private val wide = strict.copy(
            proxyName = wideProxy,
            egressAllowlist = shipped.egressAllowlist + "example.com",
        )

        /** Under the repo root, so Docker Desktop's default file sharing covers it. */
        private val scratch = File("build/tmp/jail-contract-$tag")

        private const val BUILD_TIMEOUT = 900_000L
        private const val RUN_TIMEOUT = 120_000L

        private fun docker(vararg argv: String, timeout: Long = RUN_TIMEOUT): ExecResult =
            RealCommandRunner.run(argv.toList(), timeout)

        private fun docker(argv: List<String>, timeout: Long = RUN_TIMEOUT): ExecResult =
            RealCommandRunner.run(argv, timeout)

        @BeforeAll
        @JvmStatic
        fun bringUpTheJail() {
            val version = docker("docker", "version", "--format", "{{.Server.Version}}", timeout = 30_000)
            if (version.exit != 0) {
                fail("jailContract needs a running Docker daemon (you asked for it by name): ${version.stderr.trim()}")
            }

            val built = docker(
                listOf("docker", "build", "-t", image, "-f", "docker/claude-jail/Dockerfile", "."),
                timeout = BUILD_TIMEOUT,
            )
            assertEquals(0, built.exit, "the jail image must build:\n${built.stderr.takeLast(4000)}")

            scratch.mkdirs()
            val strictConf = File(scratch, "squid-strict.conf").apply {
                writeText(JailLauncher.squidConf(strict.egressAllowlist))
            }
            val wideConf = File(scratch, "squid-wide.conf").apply {
                writeText(JailLauncher.squidConf(wide.egressAllowlist))
            }

            assertEquals(0, docker(JailLauncher.networkCreateArgv(strict)).exit, "internal network")
            listOf(strict to strictConf, wide to wideConf).forEach { (props, conf) ->
                val run = docker(JailLauncher.proxyRunArgv(props, conf.absolutePath))
                assertEquals(0, run.exit, "proxy ${props.proxyName}: ${run.stderr.trim()}")
                assertEquals(0, docker(JailLauncher.proxyConnectArgv(props)).exit, "connect ${props.proxyName}")
            }

            // squid takes a second or two to bind; poll each proxy with an allowlisted host so no case has
            // to distinguish "denied" from "not up yet".
            listOf(strict, wide).forEach(::awaitProxy)
        }

        private fun awaitProxy(props: JailProperties) {
            val deadline = System.currentTimeMillis() + 120_000
            var last: ExecResult? = null
            while (System.currentTimeMillis() < deadline) {
                last = probe(props, curl("https://api.github.com"))
                if (last.exit == 0) return
                Thread.sleep(2_000)
            }
            fail("proxy ${props.proxyName} never became usable: exit=${last?.exit} ${last?.stderr?.trim()}")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            listOf(strictProxy, wideProxy).forEach { docker("docker", "rm", "-f", it, timeout = 60_000) }
            docker("docker", "network", "rm", network, timeout = 60_000)
            docker("docker", "rmi", "-f", image, timeout = 120_000)   // the build cache survives; the tag needn't
            scratch.deleteRecursively()
        }

        /**
         * Run [probeCommand] inside a REAL jail container: every docker flag comes from the production
         * [JailLauncher.wrap], with only the trailing `claude …` replaced. That is the whole point — a
         * probe run under hand-written flags would prove nothing about what the app does.
         */
        private fun probe(
            props: JailProperties,
            probeCommand: List<String>,
            inv: JailInvocation = JailInvocation(),
        ): ExecResult {
            val wrapped = JailLauncher.wrap(listOf("claude", "-p"), props, UUID.randomUUID().toString().take(8), inv, "gh-readonly")
            val argv = wrapped.subList(0, wrapped.lastIndexOf("claude")) + probeCommand
            return docker(argv)
        }

        private fun curl(url: String, vararg extra: String) =
            listOf("curl", "-sS", "-o", "/dev/null", "-m", "20") + extra + url
    }

    @Test
    fun `an allowlisted host is reachable through the proxy`() {
        // First, because it is the control: if this fails the deny below would be meaningless.
        val result = probe(strict, curl("https://api.github.com"))
        assertEquals(0, result.exit, "api.github.com is on the shipped allowlist: ${result.stderr.trim()}")
    }

    @Test
    fun `a non-allowlisted host is denied`() {
        // THE TRIPWIRE. Add "example.com" to JailProperties.egressAllowlist and this must go red.
        val result = probe(strict, curl("https://example.com"))
        assertNotEquals(0, result.exit, "example.com is NOT on the allowlist and must not be reachable")
        assertTrue(
            result.stderr.contains("403") || result.stderr.contains("Received HTTP code"),
            "expected squid's refusal, got: ${result.stderr.trim()}",
        )
    }

    @Test
    fun `widening the allowlist flips the same probe to allowed`() {
        // Same probe, same image, same network — only the proxy's dstdomain list differs. So case 2's
        // failure is the POLICY refusing, not the plumbing being broken.
        val result = probe(wide, curl("https://example.com"))
        assertEquals(0, result.exit, "with example.com allowlisted the identical probe must succeed: ${result.stderr.trim()}")
    }

    @Test
    fun `without the proxy there is no route out at all`() {
        // --noproxy '*' makes curl ignore HTTP_PROXY entirely: the container is left to find its own way
        // out, and an --internal network has no gateway to find. Enforcement is topology, not cooperation.
        val result = probe(strict, curl("https://api.github.com", "--noproxy", "*", "-m", "5"))
        assertNotEquals(0, result.exit, "an internal network must have no route off the host")
    }

    @Test
    fun `the container cannot see the host filesystem and cannot write its own rootfs`() {
        val script = """
            if touch /usr/aiforum-probe 2>/dev/null; then echo ROOTFS_WRITABLE; exit 11; fi
            touch /work/ok || { echo WORKDIR_READONLY; exit 12; }
            if grep -Eq ' (virtiofs|9p|fuse|fuse\.[^ ]+|nfs|nfs4) ' /proc/self/mounts; then echo HOST_SHARE; exit 13; fi
            if grep -q '/Users' /proc/self/mounts; then echo HOST_PATH; exit 14; fi
            if [ -S /var/run/docker.sock ]; then echo DOCKER_SOCKET; exit 15; fi
            echo CONTAINED
        """.trimIndent()
        val result = probe(strict, listOf("sh", "-c", script))
        assertEquals(0, result.exit, "containment probe failed with ${result.stdout.trim()} / ${result.stderr.trim()}")
        assertEquals("CONTAINED", result.stdout.trim())
    }

    @Test
    fun `a credential file mount survives the tmpfs home`() {
        // The home is a tmpfs and the credential lands INSIDE it — mount ordering decides whether claude
        // finds it at all. A dummy file: no real credential is involved anywhere in this suite.
        val dummy = File(scratch, "dummy-credentials.json").apply { writeText("""{"marker":"jail-contract-dummy"}""") }
        val result = probe(
            strict,
            listOf("cat", JailLauncher.CONTAINER_CREDENTIAL),
            JailInvocation(credentialFile = dummy.absolutePath),
        )
        assertEquals(0, result.exit, "the credential must be readable inside the jail: ${result.stderr.trim()}")
        assertTrue(result.stdout.contains("jail-contract-dummy"), "got: ${result.stdout}")
    }
}
