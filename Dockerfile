FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY Server.java index.html ./
ENV PORT=8080
EXPOSE 8080
CMD ["java", "Server.java"]
