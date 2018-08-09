#!/usr/bin/env bash
HERE=$(cd $(dirname $0) 1>&2; pwd)

jenkins_path=/var/lib/jenkins
repo_dir=${jenkins_path}/workspace/_project_code
pipeline_def_dir=${jenkins_path}/workspace/_project_config
mkdir -p ${repo_dir}
ssh_key_path=${jenkins_path}/credentials/ssh/jbot-jenkinsjobs-sirca.pem
jenkins_root_dir="git@github.com:cheney-yan"
eval `ssh-agent`
cat $ssh_key_path | ssh-add -

for repo_name in $(cat ${HERE}/git_repos.json| jq -r '.[].name')
do
    jenkins_ssh_url="$jenkins_root_dir"/$repo_name
    echo "Downloading repo from ${jenkins_ssh_url} into ${repo_dir}"
    cd ${repo_dir}
    if [ ! -d "${repo_name}" ]; then
        echo "${repo_dir} does not exist. Cloning..."
        git clone ${jenkins_ssh_url}
    else
        echo "${repo_dir} already exist. Fetching change..."
        cd ${repo_dir}/${repo_name}
        git fetch --prune --force
    fi
    if [ $? -ne 0 ]; then
      echo "Failed to fetch code from repo $repo_name. Check if jbot-jenkinsjobs-sirca is the reader of the repo!"
      exit 1
    fi
done
echo "Cleaning up pipeline def cache ${pipeline_def_dir}"
rm -rf ${pipeline_def_dir}
mkdir -p ${pipeline_def_dir}
for repo_name in $(cat ${HERE}/git_repos.json| jq -r '.[].name')
do
    echo "Discovering pipeline definition for repo ${repo_name}"
    branch_names=$(cat ${HERE}/git_repos.json| jq -r ".[] |select (.name==\"${repo_name}\").jenkins_branches[]")
    echo "For repo ${repo_name}, looking at branches ${branch_names}"
    if [[ "$branch_names" = "null" ]] || [[ "$branch_names" = "" ]]
    then
        echo "Seems branch_names is not defined for this repo ${repo_name}, use 'develop' instead. "
        branch_names=develop
    fi
    for branch_name in ${branch_names}
    # if jenkins_branches not defined, this returns null
    do
        echo "Looking at repo ${repo_name}, branch ${branch_name}"
        # goto code source
        cd ${repo_dir}/${repo_name}
        git show-branch origin/${branch_name}
        if [[ $? -ne 0 ]]; then
          echo "WARN!!! branch ${branch_name} does not exist on repo ${repo_name}. The pipeline won't be generated! "
          continue
        fi
        config_path="${pipeline_def_dir}/${branch_name}/${repo_name}"
        mkdir -p ${config_path}
        for file in pipeline_def.json proj.properties
        do
            echo "Fetching file ${file} from branch ${branch_name} of project ${repo_name} "
            echo git show origin/${branch_name}:ci_hooks/${file}
            git show origin/${branch_name}:ci_hooks/${file} > ${config_path}/${file}
            if [[ $? -ne 0 ]]; then
               echo "Seems ci_hooks/${file} does not exist or coruppted."
               rm ${config_path}/${file}
            fi
        done
    done
done