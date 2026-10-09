# Third-party build tooling

`android-app/gradlew`, `android-app/gradlew.bat`, and
`android-app/gradle/wrapper/gradle-wrapper.jar` are from
[Gradle v8.11.1](https://github.com/gradle/gradle/tree/v8.11.1).
They use the Apache License, Version 2.0. The scripts retain their license
headers; the wrapper JAR includes the license at `META-INF/LICENSE`.
The Gradle distribution download is checked against its official SHA-256.

Android SDK, Kotlin, ESP-IDF and other build dependencies are downloaded by their
own tools and are not vendored in this repository. Their respective licenses
apply. This notice does not select a license for the project's original source.
