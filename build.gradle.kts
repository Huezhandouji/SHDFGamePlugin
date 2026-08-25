plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.0.2"
    kotlin("jvm")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.shadowHunterRolesPlugin:ShadowHunterRolesPlugin:1.0.0")
    testImplementation(kotlin("test"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
kotlin {
    jvmToolchain(21)
}

//自动复制到服务端文件夹
tasks.register<Copy>("copyPluginJar"){
    from(layout.buildDirectory.file("libs/${project.name}-${project.version}.jar"))
    //目标路径
    into("C:/Users/ROG/Desktop/paper1.21.11/plugins")
}

tasks.named("build"){
    finalizedBy("copyPluginJar")
}