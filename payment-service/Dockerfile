# Two stages: build with Maven, run with nothing but a JRE. The finished image
# has no compiler, no source and no build tools in it.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Dependencies first, on their own layer. Editing a .java file then costs a
# recompile, not a re-download of half of Maven Central.
#
# sharing=locked, not the default shared: the five services build at the same time
# and Maven cannot have two copies writing one repository. Shared left one build
# crawling at 2% CPU for twenty minutes. Locked makes them queue for the cache.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar

# Not root. A container that is broken into should not own the machine.
RUN addgroup -S app && adduser -S app -G app
USER app

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
