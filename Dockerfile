FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:9c35f219594c398f77af9bac2eeb14a504df5fbea753aa0c88a6a08db7df7e5e
WORKDIR /app
COPY build/libs/*.jar app.jar
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75 -Dlogback.configurationFile=logback.xml"
ENV TZ="Europe/Oslo"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
