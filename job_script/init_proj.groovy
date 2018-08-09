import common.CIJobFactory
import common.JobProperties
import common.RepoSpecificSettings
import groovy.json.JsonSlurper

def DEBUG = true
def currentPath = Thread.currentThread().executable.workspace.toString()
jenkinsProperties = new JobProperties(currentPath)
proj_pipeline_config_root = '/var/lib/jenkins/workspace/_project_config'
if (DEBUG) {
    println "Global properties: ${jenkinsProperties}"
}
def repos = new JsonSlurper().parseText(new File(currentPath, '/jobs/git_repos.json').text);

default_pipeline_def_file = currentPath + '/jobs/pipeline_def.json';

if (DEBUG) {
    println "Found the following matched repos:"
    repos.each {
        it.ssh_url = jenkinsProperties.getProperty('GIT_SSL_ROOT') + it.name
        it.git_credential_id = 'jbot-' + it.name.toLowerCase().replaceAll('-', '').replaceAll('_', '') + '-sirca-ssh-key'
        it.config_path = proj_pipeline_config_root + '/' + jenkinsProperties.get('PROJ_PROP_BRANCH') + '/' + it.name
        println "${it.name}, git URL: ${it.ssh_url}, using credential id: ${it.git_credential_id}"
    }
    println ""
}
default_pipeline = new JsonSlurper().parseText(new File(default_pipeline_def_file).text)

repoSepcificSettings = new RepoSpecificSettings(proj_pipeline_config_root, jenkinsProperties.get('PROJ_PROP_BRANCH'), out)

def all_jobs = [:]
println "Analysing all jobs"
repos.any { repo ->
    println "Working on repo: ${repo.name}"
    if (DEBUG) {
        println "Repo info: ${repo}";
    }
    config_path = proj_pipeline_config_root + '/' + jenkinsProperties.get('PROJ_PROP_BRANCH') + '/' + repo.name

    if( !(new File( config_path ).exists()) ) {
        println "=============================== IGNORE REPO ================================"
        println "Config path ${config_path} for repo: ${repo.name} does not exist. Ignore..."
        return
    }

    repo = repoSepcificSettings.updateProjectProperties(repo.name, repo)
    if (DEBUG) {
        println "Refined repo info with repo-sepcific settings: ${repo}";
    }
    customized_pipeline = repoSepcificSettings.getCustomizedPipeline(repo.name)
    try {
        if (customized_pipeline == null) {
            if (DEBUG) {
                println "No customized pipeline defiend, use default: ${default_pipeline}"
            }
            // we are doing this as groovy has no way of deep cloning objects
            pipeline_def = new JsonSlurper().parseText(new File(default_pipeline_def_file).text)
        } else {
            if (DEBUG) {
                println "Customized pipeline defiend: ${customized_pipeline}"
            }
            pipeline_def = new JsonSlurper().parseText(customized_pipeline)
            if (DEBUG) {
                println "Customized pipeline defiend: ${pipeline_def}"
            }
        }
    } catch (Exception e) {
        println "Failed to parse pipeline def for ${repo.name}";
        return
    }
    repo.pipeline_def = pipeline_def
    pipeline_def.each {
        pipeline, pipeline_detail ->
            def folder_path = "${repo.name}/${pipeline}"
            println "Working on folder: ${folder_path}"

            pipeline_detail.job_folder = folder_path
            top_job_names = []
            pipeline_detail.jobs.each {
                name, value ->
                    if (value.start_job) {
                        top_job_names << name
                    }

            }
            if (DEBUG) {
                println "============================pipeline before refining agent name==========================================";
                println pipeline_detail
                println "--------------------------------------------------------------------------------------------------------";

            }
            pipeline_detail.jobs.each {
                name, job_detail ->
                    if (job_detail.agent_name == null) {
                        job_detail.agent_name = repo.get(pipeline.toUpperCase() + '_AGENT_NAME', null)
                        if (job_detail.agent_name == null) {
                            if (repo.get('AGENT_NAME', null) == null) {
                                repo['AGENT_NAME'] = repo.name + '_agent'
                            }
                            if (pipeline.equalsIgnoreCase('release')) {
                                job_detail.agent_name = repo['AGENT_NAME'] + '_prod'
                            } else {
                                job_detail.agent_name = repo['AGENT_NAME']
                            }
                        }
                    }
            }
            if (DEBUG) {
                println "============================pipeline refined after finding agent=========================================="
                println pipeline_detail
                println "--------------------------------------------------------------------------------------------------------";
            };

            pipeline_detail.jobs.each {
                name, job_detail ->
                    downstream_jobs = [:]
                    pipeline_detail.jobs.each {
                        rest_job_name, rest_job ->
                            if (rest_job.upper_stream == name) {
                                downstream_jobs["${pipeline_detail.job_folder}/${rest_job_name}"] = (rest_job.condition == null) ? "SUCCESS" : rest_job.condition
                            }
                    }
                    job_detail.name = name
                    if (job_detail.external_downstream) {
                        job_detail.external_downstream.each {
                            external_full_name, trigger_condition ->
                                downstream_jobs[external_full_name] = trigger_condition
                        }
                    }
                    job_detail.downstream_jobs = downstream_jobs
                    job_detail.job_params=repo.get('ADDITIONAL_JOB_PARAMS', '').tokenize(',') + "VERSION"
            }

            if (DEBUG) {
                println "============================pipeline refined after finding downstream jobs=========================================="
                println pipeline_detail
                println "--------------------------------------------------------------------------------------------------------";
            };

            pipeline_detail.jobs.each {
                name, job_detail ->
                    def full_job_name=folder_path + '/' + name
                    all_jobs[full_job_name] = job_detail
                    all_jobs[full_job_name].folder_path = folder_path
            }
            if (DEBUG) {
                println "Finished for pipeline:${pipeline}"
            }
    }


    if (DEBUG) {
        println "Finished for repo:${repo.name}"
    }
}

if (DEBUG) {
    println "============================We collected all jobs and relationships========================================="
    println all_jobs
    println "--------------------------------------------------------------------------------------------------------";
};

println "============================ Generating folders/jobs/views ========================================="

repos.each { repo ->
    println "Working on repo: ${repo.name}"
    def jobFactory = new CIJobFactory(repo as Map, jenkinsProperties, out)
    println "=> Genereate Folder: ${repo.name}"
    folder(repo.name) {
        displayName("${repo.name}")
        description("All the jobs for ${repo.name}")
    }
    repo.pipeline_def.each {
        pipeline, pipeline_detail ->
            println "=> Generate SubFolder: ${pipeline_detail.job_folder}"
            folder(pipeline_detail.job_folder) {
                displayName(pipeline_detail.job_folder )
                description("${repo.name} pipeline for ${pipeline}")
            }
            top_job_names = []
            pipeline_detail.jobs.each {
                name, value ->
                    if (value.start_job) {
                        top_job_names << name
                    }
            }
            with jobFactory.getTriggerJob(pipeline_detail, top_job_names, pipeline, all_jobs)

            pipeline_detail.jobs.each {
                name, job_detail ->
                    with jobFactory.getJob(job_detail, pipeline_detail, pipeline, all_jobs)
            }
            if (DEBUG) {
                println "Finished for pipeline:${pipeline}"
            }
    }

    buildMonitorView(repo.name) {
        recurse()
        jobFilters {
            regex {
                matchType(MatchType.INCLUDE_MATCHED)
                matchValue(RegexMatchValue.DESCRIPTION)
                regex(".*::${repo.name}/.*")
            }
            regex {
                matchType(MatchType.EXCLUDE_MATCHED)
                matchValue(RegexMatchValue.DESCRIPTION)
                regex("00_Trigger.*")
            }
            status {
                matchType(MatchType.EXCLUDE_MATCHED)
                status(Status.DISABLED)
            }
        }
    }
    if (DEBUG) {
        println "Finished for repo:${repo.name}"
    }
}
