FROM gradle:8.14.3-jdk17 AS build
WORKDIR /workspace

COPY . .
RUN gradle --no-daemon :web-server:installDist

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /workspace/web-server/build/install/web-server /app

ENV PORT=8080
EXPOSE 8080

CMD ["/app/bin/web-server"]
