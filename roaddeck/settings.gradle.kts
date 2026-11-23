pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "RoadDeck"
include(":app")
if (providers.gradleProperty("roaddeckFixtures").orNull == "true") include(":fixture")
