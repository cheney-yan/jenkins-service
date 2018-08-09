package common

class RepoSpecificSettings {
    static DEBUG = true;
    private root_dir = null;
    private branch_name = null;
    private out = null;

    public RepoSpecificSettings(String config_root_dir, String branch_name, PrintStream out) {
        this.root_dir = config_root_dir
        this.branch_name = branch_name
        this.out = out
    }

    def updateProjectProperties(String repo_name, Map repo) {
        try {
            def file_path = this.root_dir + "/" + this.branch_name + "/" + repo_name + "/" + 'proj.properties'
            if (DEBUG) {
                this.out.println "Looking for repo's project properties file ${file_path}"
            }
            def propertiesFile = new File(file_path)
            def properties = new Properties();
            propertiesFile.withInputStream {
                properties.load(it)
            }
            if (DEBUG) {
                this.out.println "project: ${repo_name} has own proj.properties file to override default.";
                this.out.println "${properties}"
            }
            return repo << properties;
        } catch (Exception e) {
            this.out.println e
            return repo
        }
    }

    def getCustomizedPipeline(repo_name) {
        try {
            def file_path = this.root_dir + "/" + this.branch_name + "/" + repo_name + "/" + 'pipeline_def.json'
            if (DEBUG) {
                this.out.println "Looking for repo's pipeline definition file ${file_path}"
            }
            def file = new File(file_path)
            if (DEBUG) {
                this.out.println "project: ${repo_name} has own pipeline_def.json file to override default.";
                this.out.println "${file.text}"
            }
            return file.text
        } catch (Exception e) {
            this.out.println e
            return null
        }

    }


}
