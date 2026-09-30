# Use an official OpenJDK runtime as a parent image
FROM eclipse-temurin:21-jdk-alpine

# Set the working directory in the container
WORKDIR /app

# Copy the source code and web files into the container
COPY src/ /app/src/
COPY web/ /app/web/

# Compile the Java code
RUN mkdir -p target/classes && \
    javac -d target/classes \
    src/main/java/com/sbk/optionspricer/core/*.java \
    src/main/java/com/sbk/optionspricer/volatility/*.java \
    src/main/java/com/sbk/optionspricer/risk/*.java \
    src/main/java/com/sbk/optionspricer/tuning/*.java \
    src/main/java/com/sbk/optionspricer/benchmark/*.java \
    src/main/java/com/sbk/optionspricer/web/*.java

# Cloud Run sets the PORT environment variable (default 8080)
EXPOSE 8080

# Run the web server
CMD ["java", "-cp", "target/classes", "com.sbk.optionspricer.web.OptionsDashboardServer"]
