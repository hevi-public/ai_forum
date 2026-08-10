package com.aiforum.tier2.config

import com.aiforum.config.JailProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-2: the jail's egress policy exists in **two copies**, and this pins them together.
 *
 * Copy one is [JailProperties]' Kotlin defaults. That copy is tripwired: `jailcontract/JailContractTest`
 * builds its topology from a literal `JailProperties()`, so adding a host to the default `egressAllowlist`
 * turns its case 2 red against a real Docker daemon. Copy two is the explicit `aiforum.llm.jail` block in
 * `src/main/resources/application.yml` — and *that* is the one a running app actually binds, because an
 * explicit yml list replaces the default rather than merging with it.
 *
 * Nothing else reads copy two, so without this test the natural way to widen the perimeter — editing the
 * yml, which is where the documentation and every operator instinct points — moves the real allowlist
 * while the tripwire stays green and goes on reporting on a list no app uses. The same holds for
 * `enabled:`: a yml that switched the jail on while the defaults said off would make tier-0's and the
 * contract suite's "shipped configuration" a fiction in the other direction.
 *
 * Bound the real way rather than by parsing the file: a `@SpringBootTest` under the `test` profile asserts
 * what Spring hands the app. `application-test.yml` carries no `aiforum.llm` section at all, so every
 * value below comes from `application.yml` itself, and [com.aiforum.config.JailConfig] is deliberately not
 * profile-scoped so the bean exists here to be read.
 */
@Tag("tier2")
@SpringBootTest
@ActiveProfiles("test")
class JailYmlContractTest {

    @Autowired lateinit var bound: JailProperties

    private val pin =
        "the jailContract tripwire watches the Kotlin defaults — if you widen application.yml, widen this " +
            "pin consciously and re-scope the contract"

    @Test
    fun `the shipped yml ships the same perimeter and the same master switch as the defaults`() {
        assertEquals(JailProperties().egressAllowlist, bound.egressAllowlist, pin)
        assertEquals(JailProperties().enabled, bound.enabled, pin)
    }

    @Test
    fun `and the rest of the yml jail block matches too, so the tripwired copy is the shipped one`() {
        // Not just the two above: tier0/JailLauncherTest and jailcontract/JailContractTest each construct
        // "the shipped configuration" as `JailProperties()` and assert containment against it. Let the yml
        // drift on the image tag, the network name or the resource caps and those suites keep passing —
        // about a container this app never runs.
        assertEquals(JailProperties(), bound, pin)
    }
}
