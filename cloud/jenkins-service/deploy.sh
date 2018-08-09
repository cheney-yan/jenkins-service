#!/usr/bin/env bash
ansible-playbook  stack.yml --extra-vars "name=$1 env=dev output_file=/tmp/output"
