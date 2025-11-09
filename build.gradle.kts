// Корневой build.gradle.kts
plugins {
    // ничего не применяем напрямую
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}






