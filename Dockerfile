FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/turnforge.jar /app/turnforge.jar
RUN mkdir /data && chown 10001:10001 /data
USER 10001:10001
ENV REDY_HOST=0.0.0.0 REDY_DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/turnforge.jar"]
