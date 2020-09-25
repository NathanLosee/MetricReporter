javac -cp ./lib/* -d ./target/ ./src/com/MetricReporter/*.java
jar cvf MetricReporter.jar -C ./target/ . -C ./src/ ./res