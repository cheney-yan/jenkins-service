package common

/**
 * Get the JobProperties and check if they are properly defined.
 * @return
 */

class JobProperties extends Properties {
    public JobProperties(String currentPath){

        try {
            def env = System.getenv()
            env.each {
                this.setProperty(it.key.toUpperCase(), it.value)
            }
            def fileProp = new Properties()
            File propertiesFile = new File(currentPath + '/jobs', 'jenkins_jobs.properties')
            propertiesFile.withInputStream {
                fileProp.load(it)
            }
            fileProp.each{
                this.setProperty(it.key.toUpperCase(), it.value)
            }
        }
        catch (Exception e){
            println "Exception apppened when finding/loading jenkins_jobs.properties from dir:" +
                    currentPath + '/jobs' + ", error: "+ e + ", keep going"
        }
    }

}