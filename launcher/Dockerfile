# PopcornClaw app: the OmarchyTV launcher fork carrying the PicoClaw binary, built with Gradle (release variant,
# unsigned: the Makefile signs it with the release key in a container of the build stage, so the
# key never enters an image layer). The Makefile puts the native binaries into app/src/main/jniLibs before the build.
FROM cimg/android:2026.08.1 AS build
# AGP 8.5.2 builds with build-tools 34.0.0, which the image does not carry (its platform android-34 is there).
RUN sdkmanager --install "build-tools;34.0.0" >/dev/null
# Made by the image's user: a WORKDIR-made directory belongs to root, and Gradle writes .gradle/ into it.
RUN mkdir /home/circleci/launcher
WORKDIR /home/circleci/launcher
COPY --chown=circleci:circleci . .
RUN ./gradlew --no-daemon --console=plain assembleRelease
