import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.PreserveFirstFoundResourceTransformer

plugins {
    java
    id("com.google.protobuf") version "0.10.1"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "io.github.tonymontanov"
version = "0.1.0"

// Каталог библиотек установленного Bookmap. API берётся из той же установки,
// в которую модуль будет загружен: так версия API у компиляции и у запуска
// совпадает по построению. Переопределяется `-PbookmapLib=...` или строкой
// в ~/.gradle/gradle.properties (Windows: C:/Program Files/Bookmap/lib).
val bookmapLib: String = providers.gradleProperty("bookmapLib")
    .getOrElse("/Applications/Bookmap.app/Contents/app/lib")
val bookmapApi = files("$bookmapLib/bm-l1api.jar")

// Версии gRPC и protobuf связаны: protoc должен совпадать с protobuf-java,
// который приносит grpc-protobuf, иначе сгенерированный код и рантайм
// разойдутся. При обновлении gRPC сверять с его POM.
val grpcVersion = "1.85.1"
val protobufVersion = "3.25.9"

java {
    // bm-l1api.jar Bookmap 7.9 собран под Java 21 (class major 65): ниже
    // опуститься нельзя, выше незачем — Bookmap несёт свою JRE 25.
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(bookmapApi)

    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    // @javax.annotation.Generated в стабах gRPC; в рантайме не нужен.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // Тест точки входа читает аннотации провайдера — нужны их классы.
    testImplementation(bookmapApi)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    // Сгенерированный protoc код предупреждает о собственных deprecated-полях;
    // предупреждения проверяются только в своём коде.
    if (name == "compileJava") {
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial,-deprecation", "-Werror"))
        options.compilerArgs.add("-implicit:none")
    }
}

tasks.test {
    useJUnitPlatform()
}

// Модуль Bookmap — один jar со всеми зависимостями. Guava из зависимостей gRPC
// переносится в свой пакет: в classpath Bookmap лежит Guava 25, и при
// родительском приоритете загрузчика gRPC получил бы её вместо своей 33.x
// (NoSuchMethodError в рантайме). Остальные пакеты com.google.* (protobuf,
// common-protos, gson) переносятся тем же правилом, чтобы не зависеть от
// того, что Bookmap положит в classpath завтра. io.grpc не переносится:
// у Bookmap его нет, а сервис-провайдеры gRPC завязаны на имена классов.
val shadedPrefix = "io.github.tonymontanov.bookmap.moex.shaded"

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    // Сервис-файлы gRPC (резолверы имён, балансировщики) лежат в нескольких
    // jar под одним именем. При стратегии EXCLUDE дубли отбрасываются до
    // слияния, и из модуля молча пропадали DnsNameResolverProvider и
    // PickFirstLoadBalancerProvider из grpc-core. Поэтому дубли доходят до
    // трансформеров: сервис-файлы сливаются, а одинаковый текст Apache 2.0
    // из Guava и failureaccess остаётся одной копией.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    transform(PreserveFirstFoundResourceTransformer::class.java) {
        resources.add("META-INF/LICENSE")
    }
    relocate("com.google", "$shadedPrefix.com.google")
    relocate("io.perfmark", "$shadedPrefix.io.perfmark")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/maven/**", "META-INF/proguard/**")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    exclude("**/*.proto")
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

tasks.jar {
    // Тонкий jar без зависимостей в Bookmap не загрузится; чтобы его не
    // перепутали с модулем, у него свой классификатор.
    archiveClassifier = "plain"
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
}
