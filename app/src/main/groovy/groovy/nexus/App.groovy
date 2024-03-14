package groovy.nexus

import groovy.json.JsonSlurper
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter


def REPO_URL = "http://localhost:8081/repository/"
def USER = "myUser"
def PASSWORD = "NEXUS_PASS"

def BUCKET = "dockerregistry-s3"
def KEEP_SNAPSHOT_IMAGES = 5
def KEEP_VERSION_IMAGES = 5

def outputFile = new File("/opt/sonatype-work/nexus3/log/nexus_clear.log")
try {
    new File("/opt/sonatype-work/nexus3/log/nexus_clear.log").withWriter { writer ->
        writer.write('')
    }
} catch (Exception e) {
    println("An error occurred: ${e.message}")
}
// Custom PrintlnLog method to write to file
def PrintlnLog = { String message ->
    outputFile << message + "\n"
}

// Create a function to delete tags
def deleteTag = { String componentId ->
    def url = "http://localhost:8081/service/rest/v1/components/" + componentId
    def connection = new URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "DELETE"
    String encoded = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes())
    connection.setRequestProperty("Authorization", "Basic " + encoded)

    try {
        def responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
            PrintlnLog("Tag deleted successfully from image")
        } else {
            PrintlnLog("Failed to delete tag from image")
        }
    } catch (Exception e) {
        PrintlnLog("An error occurred: ${e.message}")
    } finally {
        connection.disconnect()
    }
}

def getTagsByImage = { image ->
    def url = "http://localhost:8081/service/rest/v1/search?name=${image}"
    def connection = new URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/json")
    String encoded = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes())
    connection.setRequestProperty("Authorization", "Basic " + encoded)

    def responseCode = connection.responseCode
    if (responseCode == HttpURLConnection.HTTP_OK) {
        def input = connection.inputStream
        def reader = new BufferedReader(new InputStreamReader(input))
        def response = new StringBuffer()
        // Define a custom comparator function to compare the dates
        def dateComparator = { a, b ->
            OffsetDateTime.parse(a.assets.lastModified[0]).compareTo(OffsetDateTime.parse(b.assets.lastModified[0]))
        }

        String line
        while ((line = reader.readLine()) != null) {
            response.append(line)
        }
        reader.close()

        def jsonSlurper = new JsonSlurper()
        def jsonResponse = jsonSlurper.parseText(response.toString())
        def itemsList = jsonResponse.items
        def snapshotTagsList = []
        def versionTagsList = []
        // Getting the list of tags by name
        itemsList.each { tag ->
            // if tag contains "-SNAPSHOT", it is a snapshot tag
            if (tag.version.contains("-SNAPSHOT")) {
                snapshotTagsList.add(tag)
            // else, it is a version tag
            } else {
                versionTagsList.add(tag)
            }
        }
        snapshotTagsList.sort(dateComparator)
        // if there are more than KEEP_SNAPSHOT_IMAGES snapshot tags, remove the oldest ones
        def counter = 0
        if (snapshotTagsList.size() > KEEP_SNAPSHOT_IMAGES) {
            counterToDelete = snapshotTagsList.size() - KEEP_SNAPSHOT_IMAGES
            snapshotTagsList.each { snapshotTag ->
                if (counter < counterToDelete) {
                    PrintlnLog("Deleting version tag:  " + snapshotTag.version)
                    PrintlnLog("Date: " + snapshotTag.assets.lastModified[0])
                    deleteTag(snapshotTag.id)
                    counter++
                }
            }
        }
        versionTagsList.sort(dateComparator)
        // if there are more than KEEP_VERSION_IMAGES version tags, remove the oldest ones
        counter
        if (versionTagsList.size() > KEEP_VERSION_IMAGES) {
            counterToDelete = versionTagsList.size() - KEEP_VERSION_IMAGES
            versionTagsList.each { versionTag ->
                if (counter < counterToDelete) {
                    PrintlnLog("Deleting version tag:  " + versionTag.version)
                    PrintlnLog("Date: " + versionTag.assets.lastModified[0])
                    deleteTag(versionTag.id)
                    counter++
                }
            }
        }
    }
}

// Function to retrieve images from the repository
def getImages = {
    def connection = new URL(REPO_URL + BUCKET + "/v2/_catalog").openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/vnd.docker.distribution.manifest.v2+json")
    String encoded = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes())
    connection.setRequestProperty("Authorization", "Basic " + encoded)

    def responseCode = connection.responseCode
    if (responseCode == HttpURLConnection.HTTP_OK) {
        def input = connection.inputStream
        def reader = new BufferedReader(new InputStreamReader(input))
        def response = new StringBuffer()

        String line
        while ((line = reader.readLine()) != null) {
            response.append(line)
        }
        reader.close()

        def jsonSlurper = new JsonSlurper()
        def catalog = jsonSlurper.parseText(response.toString())

        def images = catalog.repositories

        // Returning the list of images
        return images
    } else {
        PrintlnLog("Failed to fetch images. Response code: ${responseCode}")
        return null
    }
}

// Call the function to retrieve images
def images = getImages()
if (images) {
    images.each { image ->
        PrintlnLog("\n=== Image: $image ===\n")
        getTagsByImage(image)
    }
}
