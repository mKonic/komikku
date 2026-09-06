plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "tachiyomi.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    sqldelight {
        databases {
            create("Database") {
                packageName.set("tachiyomi.data")
                dialect(libs.sqldelight.dialects.sql)
                schemaOutputDirectory.set(project.file("./src/main/sqldelight"))
                // KMK --> verifyMigrations stays off, and not for want of trying: turning it on
                // also makes SQLDelight type-check every .sqm against the schema as of that
                // version, and migrations 1-27 reference tables and columns later migrations
                // dropped, so generateDatabaseInterface fails before verification ever runs.
                // Adopting it means rewriting the migration history first.
                //
                // Replaying from 28.db does surface real inherited drift: migration 28 adds
                // mangas_categories.last_modified_at and a trigger that mangas_categories.sq never
                // declared, so upgraded installs carry a column fresh installs lack. No generated
                // query references it, so it is inert - but it is why a plain re-baseline would be
                // papering over something rather than fixing it.
                // KMK <--
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.domain)
    implementation(projects.core.common)

    implementation(kotlinx.serialization.json)
    implementation(kotlinx.serialization.json.okio)
    implementation(kotlinx.serialization.protobuf)

    api(libs.bundles.sqldelight)
}
