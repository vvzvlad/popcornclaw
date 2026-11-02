# TV agent panel APK, built with the bare Android SDK tools (javac, d8, aapt2, zipalign, apksigner)
# instead of Gradle: there are no resources or libraries, only a manifest and a few classes.
FROM eclipse-temurin:17.0.16_8-jdk-noble AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip zip && rm -rf /var/lib/apt/lists/*
WORKDIR /sdk
# sha256 of the build-tools;34.0.0 and platforms;android-28 (r06) archives whose sha1 matches
# Google's repository2-3.xml.
RUN curl -fsSLO https://dl.google.com/android/repository/build-tools_r34-linux.zip \
 && curl -fsSLO https://dl.google.com/android/repository/platform-28_r06.zip \
 && printf '%s\n' \
      'e858c4b60069d0431051b225d384413b1643e1289b00a4825aed347f25bd510f  build-tools_r34-linux.zip' \
      '8452dbbf9668a428abb243c4f02a943b7aa83af3cca627629a15c4c09f28e7bd  platform-28_r06.zip' \
    | sha256sum -c - \
 && unzip -q build-tools_r34-linux.zip -d bt && mv bt/* build-tools \
 && unzip -q platform-28_r06.zip -d pf && mv pf/* platform \
 && rm -r bt pf *.zip

WORKDIR /app
COPY AndroidManifest.xml debug.keystore ./
COPY src src
ARG BT=/sdk/build-tools
ARG JAR=/sdk/platform/android.jar
# android.jar has no LambdaMetafactory.metafactory; javac needs the stubs for lambdas, d8 desugars them.
RUN mkdir classes dex \
 && javac -source 8 -target 8 -bootclasspath $JAR:$BT/core-lambda-stubs.jar -Xlint:-options -encoding UTF-8 \
      -d classes $(find src -name '*.java') \
 && $BT/d8 --release --min-api 28 --lib $JAR --output dex $(find classes -name '*.class') \
 && $BT/aapt2 link --manifest AndroidManifest.xml -I $JAR -o unsigned.apk \
 && (cd dex && zip -q ../unsigned.apk classes.dex) \
 && $BT/zipalign -p -f 4 unsigned.apk aligned.apk \
 && $BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
      --ks-key-alias androiddebugkey --out tvagent-panel.apk aligned.apk \
 && $BT/apksigner verify tvagent-panel.apk

FROM scratch
COPY --from=build /app/tvagent-panel.apk /tvagent-panel.apk
