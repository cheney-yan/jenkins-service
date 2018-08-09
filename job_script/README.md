# Jenkins initialization guide

When a new Jenkins server has been initialized by cloud script, there are some plugins/settings needs be manually set.
Otherwise, if the new jenkins recovers data from backup (which could be a copy from another old jenkins instance), most of the settings/plugins/jobs are to be moved to the new instance, and the following steps can be saved.
For more details about recovery, check README.md under cloud directory.

# Initialize Jenkins

When accessing the jenkins server from a web browser for the first time, follow the guide and finalize with minimal installation.

## Jenkins Location
In Jenkins->Configure system, check if JenkinsURL is correctly set. It should be the domain name or load balancer's URL of the Jenkins server.
Also configure the `System Admin e-mail address`, for example, 'jenkins@domain.com.au'. This will be used as the sender of email notifications.

## Plugins
Manually installation the following plugins (Jenkins-> Manage Jenkins-> Manage Plugins)

| Plugin                               | Version | Comment |
|:-------------------------------------|:--------|:--------|
| Artifactory                          |         |         |
| JobDSL                               |         |         |
| Environment Injector                 |         |         |
| Parameterized Trigger                |         |         |
| Github Authentication                |         |         |
| Violations                           |         |         |
| cobertura                            |         |         |
| SSH Agent                            |         |         |
| Amazon EC2 Plugin                    |         |         |
| Amazon Web Services SDK              |         |         |
| Build Monitor View                   |         |         |
| View Job Filters                     |         |         |
| Build Name Setter                    |         |         |
| Matrix Authorization Strategy Plugin |         |         |
| No Agent Job Purge                   |         |         |
| Build with parameters                |         |         |

For the plugins with older versions, you need to manually download from source and install.

## Credentials setting up

### jbot-PUBLISHER-sirca API token

Check up:
https://confluence.sirca.org.au/display/CT/Jenkins+Admin+Instructions#JenkinsAdminInstructions-InputJenkinspublisheraccesstoken

## EC2CLoud credential 
TODO: update in future Jenkins Stories. and move this bit to confluence.
Follow this instruction to configure the github plugins using automatic mode.

## Github OAuth setup
Check 
https://confluence.sirca.org.au/display/CT/Jenkins+Admin+Instructions#JenkinsAdminInstructions-GithubOAuthsetup

# MasterSeeder job setup

## install tools needed

### Install Gradle
Use jenkins to install gradle (in doing this, gradle will be automatically built in jenkins configs, which can be stored and recovered together with other config data)

Go to Jenkins-Global Tool Configuration->Gradle,  and select "add gradle", give it a name (for example `3.1`) and select `install automatically` from Grdle.org, version `3.1`

Click `save` to install gradle.

## create MasterSeeder job

Log in Jenkins as admin user and:
- Manage Jenkins => Configure Security => Deselect "Enable script security for Job DSL scripts".
- New Item (name: MasterSeeder, type: freestyle job), then Next
- General->Github project: "https://github.com/sirca/jenkins_jobs"
- General->Restrict where this project can be run: "master"
- Source Code Management=>Git=>Repositories: "git@github.com:sirca/jenkins_jobs"
- Source Code Management=>Git=>Credentials: select the credential of "jbot-jenkinsjobs-sirca"
- Source Code Management=>Git=>Branches to build: the branch where we want to use. When you are actively developing this project, you may want to use your own branch name, like "*/feature/developing_jenkins_2". For now, always use "*/develop".
- Build => Build Triggers: choose `Build periodically`, and set cron schedule as "0 12 * * *"
- Build => Add Build Step: "Execute Shell", with content `bash jobs/prepare_local_pipeline_def.sh`.
- Build => Add Build Step: "Invoke Gradle Script", use the gradle version we have installed in the previous steps.
- Invoke Gradle Script => Build File: "jobs/jenkins_dependency.gradle", choose the installed gradle version.
- Add Build Step => Process Job DSLs:
    - Tick "look on file system".
    - Process Job DSLs:DSL Scripts "jobs/init_proj.groovy"
    - Process Job DSLs:Action for removed jobs, and Action for removed views: "delete"
    - Process Job DSLs => Advanced => Additional classpath: "jobs/libs/*"
- Save

Now manually kick off  `MasterSeeder`, then you will notice the seeder is generating other folders and jobs for the sirca repos.
Also this job is configured to be timely triggered during night.

