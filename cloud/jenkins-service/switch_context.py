#!/usr/bin/env python
# -*- coding: utf-8 -*-
# TL;DR
import re, sys
reload(sys)
sys.setdefaultencoding('utf8')
from collections import OrderedDict

import boto3
import cli.log

param_root = '/ds/jenkins'

parameters = OrderedDict([
    ('%s/jenkins/github_api_client_id' % param_root,
     "The github api client id for jenkins.rozetta.com.au"),
    ('%s/jenkins/github_api_client_secret' % param_root,
     "The github api client secret for jenkins.rozetta.com.au"),
    ('%s/jenkins/root_url' % param_root,
     "The github root_url for jenkins.rozetta.com.au"),
    ('%s/jenkins-next/github_api_client_id' % param_root,
     "The github api client id for jenkins-next.rozetta.com.au"),
    ('%s/jenkins-next/github_api_client_secret' % param_root,
     "The github api client secret for jenkins-next.rozetta.com.au"),
    ('%s/jenkins-next/root_url' % param_root,
     "The github root_url for jenkins-next.rozetta.com.au"),
])


@cli.log.LoggingApp(description="""
""")
def switch_context(app):
    check_params(params=app.params)
    session = boto3.Session(profile_name=app.params.profile)
    ssm_client = session.client('ssm', region_name=app.params.region)
    if app.params.action == 'init':
        init_parameter_store(ssm_client, app.params.kms_key)
    else:
        jenkins_client_id = get_value(ssm_client,
                                      '%s/%s/github_api_client_id' % (param_root, app.params.context))
        jenkins_client_secret = get_value(ssm_client,
                                          '%s/%s/github_api_client_secret' % (param_root, app.params.context))
        root_url = get_value(ssm_client,
                                          '%s/%s/root_url' % (param_root, app.params.context))
        update_jenkins_credential(jenkins_client_id, jenkins_client_secret)
        update_jenkins_root_url(root_url)
        apply_master_seeder_nexus_branch_tag(app.params.context)
        adjust_agent_labels(app.params.context)
switch_context.add_param("-r", "--region", help="region", default=None)
switch_context.add_param("-p", "--profile", help="aws profile", default=None)
switch_context.add_param("-k", "--kms_key", help="If provided, then use this kms_key (or alias) to encrypt the value",
                         default=None)
switch_context.add_param("-c", "--context", help="what context to switch this jenkins service", required=False,
                         choices=['jenkins', 'jenkins-next'])
switch_context.add_param("action", help="what context to switch this jenkins service",
                         choices=['init', 'switch'], default='switch')


def update_jenkins_credential(git_client_id, git_client_secret, file_path='/var/lib/jenkins/config.xml'):
    with open(file_path, 'r') as f:
        content = f.read()

    content = re.sub(r'<clientID>.*</clientID>', '<clientID>%s</clientID>' % git_client_id, content)
    content = re.sub(r'<clientSecret>.*</clientSecret>', '<clientSecret>%s</clientSecret>' % git_client_secret,
                      content)

    # Write the file out again
    with open(file_path, 'w') as f:
        f.write(content)

def update_jenkins_root_url(root_url, file_path='/var/lib/jenkins/jenkins.model.JenkinsLocationConfiguration.xml'):
    with open(file_path, 'r') as f:
        content = f.read()

    content = re.sub(r'<jenkinsUrl>.*</jenkinsUrl>', '<jenkinsUrl>%s</jenkinsUrl>' % root_url, content)

    # Write the file out again
    with open(file_path, 'w') as f:
        f.write(content)


def apply_master_seeder_nexus_branch_tag(context, file_path='/var/lib/jenkins/jobs/MasterSeeder/config.xml'):
    with open(file_path, 'r') as f:
        filedata = f.read()
    if context == 'jenkins':
        filedata = re.sub(r'<name>.*</name>', '<name>%s</name>' % '*/develop', filedata)

    elif context == 'jenkins-next':
        filedata = re.sub(r'<name>.*</clientID>', '<clientID>%s</clientID>' % '*/jenkins-next', filedata)

    # Write the file out again
    with open(file_path, 'w') as f:
        f.write(filedata)


def adjust_agent_labels(context, file_path='/var/lib/jenkins/config.xml'):
    with open(file_path, 'r') as f:
        content = f.read()

    def change_tag(matchObj):
        lables = matchObj.group(1).split()
        if context == 'jenkins':
            context_label = ','.join([l[13:] if l.startswith('jenkins_next_') else l for l in lables])
        elif context == 'jenkins-next':
            context_label = ','.join([l if l.startswith('jenkins_next_') else
                                      "%s%s" % ('jenkins_next_', l) for l in lables])
        else:
            raise StandardError('Unknown context:%s' % context)
        return '<labels>' + context_label + '</labels>'

    content = re.sub(r'<labels>(.*)</labels>', change_tag, content)

    # Write the file out again
    with open(file_path, 'w') as f:
        f.write(content)


def store_value(ssm_client, key, value, kms_key=None):
    ssm_client.put_parameter(
        Name=key,
        KeyId=kms_key,
        Value=value,
        Type='SecureString' if kms_key else 'String',
        Overwrite=True
    )
    ssm_client.add_tags_to_resource(
        ResourceType='Parameter',
        ResourceId=key,
        Tags=[
            {
                'Key': 'User',
                'Value': 'Jenkins'
            },
        ]
    )


def get_value(ssm_client, name):
    resp = ssm_client.get_parameter(
        Name=name,
        WithDecryption=True
    )
    if resp and resp.get('Parameter'):
        return resp['Parameter']['Value']
    else:
        raise StandardError("Failed to get %s from parameter store. Try 'init' commmand to"
                            "initialize them in the first place" % name)


def init_parameter_store(ssm_client, kms_key=None):
    for param, explain in parameters.items():
        input_str = get_input("Please input value for '%s'" % explain)
        store_value(ssm_client, param, input_str, kms_key=kms_key)
    print "You have finished all the inputs."


def get_input(prompt, can_be_empty=False):
    print("%s(%s%s)" % (prompt, 'cannot be empty!' \
        if not can_be_empty else '', 'RETURN to finish.'))
    input_str = raw_input()
    while not can_be_empty and not input_str.strip():
        print 'Please type something.'
        input_str = raw_input()

    return input_str


def check_params(params):
    if params.action == 'init' and params.context:
        raise StandardError("No context is needed when initializing the contexts.")
    if params.action == 'switch' and not params.context:
        raise StandardError("Must provide context when switching.")


if __name__ == "__main__":
    switch_context.run()
