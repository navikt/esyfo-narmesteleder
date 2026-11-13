package no.nav.syfo.platform.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ApplicationConfigLoaderTest :
    FunSpec({
        listOf(
            "dev-gcp" to listOf("application-dev-gcp.conf", "application.conf"),
            "prod-gcp" to listOf("application-prod-gcp.conf", "application.conf"),
            null to listOf("application.conf"),
            " " to listOf("application.conf"),
        ).forEach { (cluster, resources) ->
            test("cluster '$cluster' loads $resources") {
                configResources(cluster) shouldBe resources
            }
        }

        test("the cluster file overrides application.conf") {
            parseConfigResources(*configResources("override-test").toTypedArray()).getString("maintenanceTask.interval") shouldBe "from-cluster-file"
        }
    })
