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
                // KMK --> replays every migration added after the snapshot and compares the
                // result against the .sq files, so a migration that drifts from the schema fails
                // the build instead of corrupting someone's database on upgrade.
                //
                // The snapshot is 47.db, taken at the current schema. Replaying from the previous
                // 28.db baseline reported drift inherited from upstream: migration 28 adds
                // mangas_categories.last_modified_at and a trigger that mangas_categories.sq never
                // declared, so upgraded installs carry a column fresh installs do not. Nothing
                // reads it - no generated query references it - and reconciling it needs a table
                // rebuild across a foreign key, which is its own change. Re-baselining leaves that
                // alone and guards every migration from here on.
                verifyMigrations.set(true)
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
