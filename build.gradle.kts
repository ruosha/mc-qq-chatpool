plugins {
    java
}

group = "com.dsh"
version = "1.0.0"
description = "Paper 26.2 插件：聊天池 —— Minecraft 与 QQ 群聊双向互通"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.132-stable")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.jar {
    archiveBaseName.set("mc-qq-chatpool")
    archiveClassifier.set("")
}
