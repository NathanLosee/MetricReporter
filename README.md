##### How to use
Run with the following command on the terminal:
`java -jar MetricReporter.jar "{config}" "{startDateTime}" "{duration}" "{exportType}" "{exportName}" "{overwrite}"`

config = The application you are gathering and reporting metrics for. Use "Online" or "Focus".
startDateTime = The starting date and time for metric gathering. It must be of the form "MM/dd/yyyy-HH:mm" (24-hr).
duration = The duration in minutes after the starting data and time to gather metrics for. It must be an integer.
exportType = The format and destination of the exported data. Use "CSV" or "Confluence".
exportName = The name of the resulting file (for CSV) or page (for Confluence).
overwrite = Tells the program whether to overwrite the existing file or page if it already exists.

You will also want to make sure the username and token properties in `metrics.properties` are filled out properly.

##### How to build
Compile the project by running the `Compile.bat` file