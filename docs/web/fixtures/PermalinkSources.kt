package fixtures

import robofight.fireline.Missions
import java.util.Base64

/**
 * Docs harness (not app source): records the LIVE authored firmware sources of
 * every mission (reference plus failing variants) as base64 so the permalink
 * fixture generator (`permalink_fixture_gen.py`) can build `FLW1-` links from
 * engine-authored text instead of hand-copied strings (robofight-step-records:
 * fixtures come from the generator, never typed).
 *
 * Output: one TSV line per scenario:
 *   scenario \t mission_id \t mission_name \t firmware_name \t seed_override \t source_base64
 * Seed override 0 means "authored seed". Written to stdout; the committing step
 * redirects it to `permalink_sources.tsv`.
 */
fun main() {
    val enc = Base64.getEncoder()
    println("scenario\tmission_id\tmission_name\tfirmware_name\tseed_override\tsource_base64")
    for (mission in Missions.ALL) {
        val storeBase = mission.name.uppercase().replace(' ', '-')
        println(
            listOf(
                "${mission.id}-reference",
                mission.id,
                mission.name,
                if (mission.id == 2) "CORRIDOR-REF.ASM" else storeBase + "-REF.ASM",
                0,
                enc.encodeToString(mission.referenceSource.encodeToByteArray()),
            ).joinToString("\t")
        )
        for ((label, source, _) in mission.failingSources) {
            println(
                listOf(
                    "${mission.id}-failing-" + label.lowercase().replace(' ', '-'),
                    mission.id,
                    mission.name,
                    storeBase + "-" + label.uppercase().replace(' ', '-') + ".ASM",
                    0,
                    enc.encodeToString(source.encodeToByteArray()),
                ).joinToString("\t")
            )
        }
    }
    // The permalink half's seed-override case: mission 02 reference firmware,
    // an explicit seed override (seed 7) - the only scenario that exercises
    // seedOverride != 0 (§8 "mission id, seed override, and firmware source").
    val m2 = Missions.ALL.first { it.id == 2 }
    println(
        listOf(
            "2-reference-seed7",
            2,
            m2.name,
            "CUSTOM-SEED.ASM",
            7,
            enc.encodeToString(m2.referenceSource.encodeToByteArray()),
        ).joinToString("\t")
    )
}
