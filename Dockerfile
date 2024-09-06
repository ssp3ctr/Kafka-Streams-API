# Use an official Maven image to build the application
FROM maven:3.8.6-openjdk-11 AS build

# Set the working directory inside the container
WORKDIR /app

# Copy the Maven project files (pom.xml and source code)
COPY pom.xml .
COPY src ./src

# Copy the .env file to the container
COPY .env . 

# Run Maven to build the application
RUN mvn clean package -DskipTests

# Use an official JRE image for the final stage
FROM openjdk:11-jre-slim

# Set the working directory inside the container
WORKDIR /app

# Copy the JAR file from the build stage
COPY --from=build /app/target/kafka-stream-app.jar /app/kafka-stream-app.jar

# Copy the .env file to the container
COPY .env .  

# Command to run the application
CMD ["java", "-jar", "/app/kafka-stream-app.jar"]
