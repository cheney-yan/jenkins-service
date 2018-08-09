package common


class CIJobFactory {
    Map repo = null
    Properties envProps = null
    static DEBUG = true;

    def CONTEXT_FILE = "env.properties"
    def TRIGGER_JOB_NAME = "00_Trigger"
    def out = null

    public CIJobFactory(Map repo, Properties envProps, out) {
        this.repo = repo
        this.out = out
        this.envProps = envProps
    }

    private boolean canAutomaticallyTrigger(upperStreamJob, downStreamJob, all_jobs_info) {
        if (all_jobs_info[downStreamJob] == null
                || all_jobs_info[downStreamJob].agent_name == null
                || !all_jobs_info[downStreamJob].agent_name.endsWith('_prod')
        ) {
            return true
        }
        if (all_jobs_info[upperStreamJob] != null
                && all_jobs_info[upperStreamJob].agent_name != null
                && all_jobs_info[upperStreamJob].agent_name == all_jobs_info[downStreamJob].agent_name) {
            return true
        }
        this.out.println "${upperStreamJob} cannot automatically trigger ${downStreamJob}."
        return false
    }

    private String generateManualTriggerLink(job_full_name, parameters) {
        def url = ''
        for (path in job_full_name.split('/')) {
            url += '/job/'
            url += path
        }
        url += '/parambuild/?'
        for (param in parameters) {
            url += "&" + param + '=$' + param
        }
        return url
    }

    public getJob(Map options, pipeline_detail, pipeline_name, all_jobs_info) {
        // full name of the job, including folder name
        def jobName = "${pipeline_detail.job_folder}/${options.name}"
        this.out.println "=> Generatre Job with Name: ${jobName}"
        if (DEBUG) {
            this.out.println "Params: options: ${options}: pipeline: ${pipeline_detail}"
        }
        // the script name of the job
        def script = "${this.envProps.getProperty('CI_HOOK_DIR')}/${options.get('script')}"
        def gitRepo = this.repo.ssh_url
        def projType = this.repo.get('PROJECT_TYPE', '').tokenize(',')
        def runLint = this.repo.get('RUN_LINT', 'false').toLowerCase()
        def reportCover = this.repo.get('REPORT_COVERAGE', 'false').toLowerCase()
        def reportTestResult = this.repo.get('REPORT_TEST_RESULT', 'false').toLowerCase()
        def additionalJobParams = this.repo.get('ADDITIONAL_JOB_PARAMS', '').tokenize(',')
        def slaveLabel = options.get('agent_name')
        def testResultsPattern = this.repo.get('TEST_RESULTS_PATTERN', '**/junit-*.xml,**/test-results.xml,**/TEST-*.xml')
        def branchPatternOverride = this.repo.get(pipeline_name.toUpperCase() + "_BRANCH_PATTERN", null)
        if (options.bypass_if_not_defined == null) { //by default, should not bypass
            options.bypass_if_not_defined = 'false'
        }
        return {
            job(jobName) {
                logRotator(-1, 30)
                label(slaveLabel)
                description("${options.name}::${pipeline_detail.job_folder}")

                wrappers {
                    timestamps()
                    buildName('#${BUILD_NUMBER}_${GIT_BRANCH}_${VERSION}')
                    sshAgent(this.repo.git_credential_id)
                }
                scm {
                    git {
                        remote {
                            url(gitRepo)
                            credentials(this.repo.git_credential_id)
                            if (branchPatternOverride != null) {
                                branchPatternOverride.tokenize(',').each {
                                    branch(it)
                                }
                            } else {
                                pipeline_detail.branches.each {
                                    branch(it)
                                }
                            }

                        }
                        extensions {
                            cleanBeforeCheckout()
                            localBranch()
                            pruneBranches()
                        }
                    }
                }
                parameters {
                    all_jobs_info[jobName].job_params.each {
                        param ->
                            stringParam(param, '${GIT_VERSION}', 'Build which SHA1 of commit. If not specified, it will use the GIT_VERSION from context.')
                    }
                }
                steps {
                    shell("""
                        git reset --hard \${VERSION}
                        [ \$? -ne 0 ] && >&2 echo "Could not find version \${VERSION}. Is the branch deleted?" && exit 1
                        # pass variables down to next job
                        env > env.properties
                        # not calling script only when file does not exist and bypass_if_not_exist
                        ( [ ! -f ${script} ] && ${options.bypass_if_not_defined} ) || chmod +x ${script} && ${script}
                    """)
                }
                if (options.junit_results && reportTestResult == 'true') {
                    publishers {
                        archiveJunit(testResultsPattern)
                    }
                }
                if (options.lint && runLint == 'true') {
                    if (projType.contains('python')) {
                        publishers {
                            violations {
                                pylint(0, 999, 999, '**/pylint.log')
                            }
                        }
                    }
//                    if (projType.contains('javascript')) {
//                        publishers {
//                            violations {
//                                jslint(0, 999, 999, '**/eslint.log')
//                            }
//                        }
//                    }
                }
                if (options.coverage && reportCover == 'true') {
                    publishers {
                        cobertura('**/*coverage.xml') {
                            failNoReports(false)
                        }
                    }
                }
                if (DEBUG) {
                    this.out.println "${options.name}: downstream jobs ${options.downstream_jobs}";
                }

                publishers {
                    mailer('', true, true)
                    // publish this build status
                    gitHubCommitStatusSetter{
                       commitShaSource {
                           manuallyEnteredShaSource {
                               sha('${VERSION}')
                           }
                       }
                       contextSource {
                            manuallyEnteredCommitContextSource {
                               context(jobName)
                            }
                       }
                       // publish status by default behavior. (success->success)
                    }
                }

                if (options.downstream_jobs.size() > 0) {
                    options.downstream_jobs.each {
                        downStreamJobName, triggerCondition ->
                          if (!canAutomaticallyTrigger(jobName, downStreamJobName, all_jobs_info)) {
                              steps {
                                  shell("""#!/bin/bash
                                     echo 'Automated triggering to downstream job ${downStreamJobName} is disabled. Jenkins'
                                     echo 'does not automatically trigger high priority prod jobs from low priority non-prod'
                                     echo 'jobs. Please login account with ProdReleaser permission and trigger the job with'
                                     echo 'the following link'
                                     echo '===== ${downStreamJobName} TRIGGER LINK =================='
                                     echo "\$JENKINS_URL${generateManualTriggerLink(
                                          downStreamJobName,
                                          all_jobs_info[downStreamJobName].job_params
                                  )
                                  }"
                                     echo '=========================================================================='
                                  """)
                              }
                          }
                    }
                    publishers {
                        downstreamParameterized {
                            options.downstream_jobs.each {
                                name, triggerCondition ->
                                    if (canAutomaticallyTrigger(jobName, name, all_jobs_info)) {

                                        trigger("${name}") {
                                            condition(triggerCondition)
                                            parameters {
                                                propertiesFile(CONTEXT_FILE, true)
                                            }
                                        }
                                    }
                            }

                        }
                    }
                }

            }
        }
    }

    public getTriggerJob(Map options, downstream_jobs, String pipeline_name, all_jobs_info) {
        def jobName = "${options.job_folder}/${TRIGGER_JOB_NAME}"
        this.out.println "=> Generate Job with Name: ${jobName}"
        def gitRepo = this.repo.ssh_url
        def branchPatternOverride = this.repo.get(pipeline_name.toUpperCase() + "_BRANCH_PATTERN", null)
        return {
            job(jobName) {
                logRotator(-1, 10)
                description("${TRIGGER_JOB_NAME}::${options.job_folder}")
                wrappers {
                    timestamps()
                    buildName('#${BUILD_NUMBER}_${GIT_BRANCH}_${GIT_REVISION}')
                    sshAgent(this.repo.git_credential_id)
                }
                scm {
                    git {
                        remote {
                            url(gitRepo)
                            credentials(this.repo.git_credential_id)
                            if (branchPatternOverride != null) {
                                if (DEBUG) {
                                    this.out.println "Using project branch pattern: ${branchPatternOverride}";
                                }
                                branchPatternOverride.tokenize(',').each {
                                    branch(it)
                                }
                            } else {
                                options.branches.each {
                                    branch(it)
                                }
                            }

                        }
                        extensions {
                            cleanBeforeCheckout()
                            localBranch()
                            pruneBranches()
                        }
                    }
                }
                triggers {
//                    githubPush()
                    scm('* * * * * ') // This can cause github API easily get throttled by github.
                }
                steps {
                    shell("""
                       echo "VERSION=\$(git rev-parse --verify HEAD)" >> ${CONTEXT_FILE}
                    """)
                }
                downstream_jobs.each {
                    def downStreamJobName = "${options.job_folder}/${it}"
                    if (!canAutomaticallyTrigger(jobName, downStreamJobName, all_jobs_info)) {
                        steps {
                            shell("""#!/bin/bash
                               source ./env.properties
                               echo 'Automated triggering to downstream job ${downStreamJobName} is disabled. Jenkins'
                               echo 'does not automatically trigger high priority prod jobs from low priority non-prod' 
                               echo 'jobs. Please login account with ProdReleaser permission and trigger the job with' 
                               echo 'the following link' 
                               echo '===== ${downStreamJobName} TRIGGER LINK =================='
                               echo "\$JENKINS_URL${generateManualTriggerLink(
                                    downStreamJobName,
                                    all_jobs_info[downStreamJobName].job_params
                            )      
                            }" 
                               echo '=========================================================================='
                            """)
                        }
                    }
                }
                publishers {
                    // set PENDING status for Github PR, marking they must pass each jobs under the same folder(pipeline)
                    if (DEBUG) {
                      this.out.println "=> All jobs: ${all_jobs_info}"
                      this.out.println "=> Current Job options: ${options}"
                    }
                    sameFolderJobs = all_jobs_info.findAll { it.value.folder_path == options.job_folder }.keySet()
                    if (DEBUG) {
                      this.out.println "=> All jobs under the same folder: ${sameFolderJobs}"
                    }
                    sameFolderJobs.each { pipelineJobName ->
                        gitHubCommitStatusSetter{
                           commitShaSource {
                               manuallyEnteredShaSource {
                                   sha('${GIT_REVISION}')
                               }
                           }
                           contextSource {
                                manuallyEnteredCommitContextSource {
                                   context("${pipelineJobName}")
                                }
                           }
                           statusResultSource {
                               conditionalStatusResultSource {
                                  results{
                                     anyBuildResult {
                                        state("PENDING")
                                     }
                                  }
                               }
                           }
                        }
                    }
                    downstreamParameterized {
                        downstream_jobs.each {
                            def downStreamJobName = "${options.job_folder}/${it}"
                            if (canAutomaticallyTrigger(jobName, downStreamJobName, all_jobs_info)) {
                                trigger("${downStreamJobName}") {
                                    condition('SUCCESS')
                                    parameters {
                                        propertiesFile(CONTEXT_FILE, true)
                                    }
                                }
                            }
                        }

                    }
                }

            }
        }
    }


}

