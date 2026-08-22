// Jenkins Pipeline job: "Pipeline script from SCM" pointing at this repo,
// with Script Path = infra-GitlabBackup.groovy
//
// Runs ansible-playbook (playbooks/backup_and_verify.yml) on a schedule:
// exports every project from the source GitLab instance, downloads the
// archives, then proves each one is actually restorable by importing it
// into a disposable throwaway GitLab CE container (docker-compose) and
// checking the restored project really has its repository content. See
// README.md for the full design.
//
// One-time Jenkins setup required before this works:
//   1. Set AGENT_NODE_LABEL below to the real label of the agent node that
//      has docker + docker-compose + ansible-core installed and can reach
//      the source GitLab instance (this repo's own docker-compose.gitlab-source.yml,
//      or a real self-hosted GitLab once you point gitlab_url at it).
//   2. Create a "Secret text" credential holding the GitLab Personal Access
//      Token (api scope) for the source instance, with ID
//      GITLAB_TOKEN_CREDENTIALS_ID below.
//   3. Adjust GITLAB_URL below (or override via the job's own build
//      parameter) if the source GitLab isn't this repo's default
//      throwaway container on localhost:8929.
//   4. This job must not run concurrently with itself (disableConcurrentBuilds
//      below) - the restore step uses a single fixed container
//      name/port (gitlab-restore:8930), so two runs would collide.

def AGENT_NODE_LABEL = 'CHANGE_ME_GITLAB_BACKUP_AGENT_LABEL'
def GITLAB_TOKEN_CREDENTIALS_ID = 'gitlab-backup-token'

pipeline {
    agent { label AGENT_NODE_LABEL }

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    triggers {
        // Nightly at 02:00. Adjust to taste - this is a schedule, not a
        // hardcoded requirement.
        cron('0 2 * * *')
    }

    parameters {
        string(name: 'GITLAB_URL', defaultValue: 'http://localhost:8929', description: 'Source GitLab instance to back up')
        booleanParam(name: 'VERIFY_RESTORE', defaultValue: true, description: 'After backing up, prove each backup is restorable via a disposable GitLab CE container (slower - a few minutes per run for the throwaway instance to boot)')
    }

    stages {
        stage('Checkout automation repo') {
            steps {
                git branch: 'main', url: 'https://github.com/soroush67/gitlab-backup-with-jenkins-ansible.git'
            }
        }

        stage('Backup GitLab projects') {
            when { expression { !params.VERIFY_RESTORE } }
            environment {
                GITLAB_URL = "${params.GITLAB_URL}"
            }
            steps {
                withCredentials([string(credentialsId: GITLAB_TOKEN_CREDENTIALS_ID, variable: 'GITLAB_TOKEN')]) {
                    sh '''
                        set -e
                        ansible-playbook playbooks/backup.yml \
                          -e gitlab_url="$GITLAB_URL" \
                          -e gitlab_token="$GITLAB_TOKEN"
                    '''
                }
            }
        }

        stage('Backup + verify restore') {
            when { expression { params.VERIFY_RESTORE } }
            environment {
                GITLAB_URL = "${params.GITLAB_URL}"
            }
            steps {
                withCredentials([string(credentialsId: GITLAB_TOKEN_CREDENTIALS_ID, variable: 'GITLAB_TOKEN')]) {
                    sh '''
                        set -e
                        ansible-playbook playbooks/backup_and_verify.yml \
                          -e gitlab_url="$GITLAB_URL" \
                          -e gitlab_token="$GITLAB_TOKEN"
                    '''
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'backups/*/manifest.json', allowEmptyArchive: true
        }
    }
}
