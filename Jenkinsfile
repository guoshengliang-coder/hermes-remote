// 与 .github/workflows/ci.yml 等价的 Jenkins 流水线。
//
// 与 GitHub Actions 上的设计保持一致：普通 CI 是刻意不授权的 —— 它拿不到
// 规范 debug 签名密钥，也拿不到任何发布密钥，因此只跑无需签名材料的检查。
// 产出可分发制品的流程独立成 Job（对应 android-release.yml / gateway-oci.yml）。
//
// agent 标签：
//   linux — Ubuntu 构建机，提供 JDK 21 / Node 22 / Docker / Android SDK
//   macos — macOS 构建机，仅 desktop 阶段需要（Apple 工具链无法在其他系统运行）

pipeline {
  agent none

  parameters {
    booleanParam(
      name: 'RUN_DESKTOP',
      defaultValue: false,
      description: 'macOS 桌面端检查。需要有 macos 标签的 agent 在线，否则会一直等待。'
    )
  }

  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '30'))
    disableConcurrentBuilds(abortPrevious: true)
    timeout(time: 45, unit: 'MINUTES')
  }

  stages {
    stage('Checks') {
      parallel {

        stage('node') {
          agent { label 'linux' }
          environment {
            // 测试库用 55432，避开宿主机上可能已在跑的 5432
            ACCOUNT_TEST_DATABASE_URL = 'postgresql://hermes_test:hermes_test_password@127.0.0.1:55432/hermes_test'
            RUN_NETWORK_TESTS = '1'
            PG = "pg-jenkins-${env.BUILD_NUMBER}"
          }
          steps {
            checkout scm
            sh '''
              set -eu
              cleanup(){ docker rm -f "$PG" >/dev/null 2>&1 || true; }
              trap cleanup EXIT
              docker rm -f "$PG" >/dev/null 2>&1 || true

              docker run -d --name "$PG" \
                -e POSTGRES_DB=hermes_test \
                -e POSTGRES_USER=hermes_test \
                -e POSTGRES_PASSWORD=hermes_test_password \
                -p 127.0.0.1:55432:5432 \
                postgres:18-alpine

              for i in $(seq 1 20); do
                docker exec "$PG" pg_isready -U hermes_test -d hermes_test >/dev/null 2>&1 && break
                sleep 3
              done

              npm ci --ignore-scripts
              npm run build
              npm test
            '''
          }
        }

        stage('android') {
          agent { label 'linux' }
          environment {
            ANDROID_SDK_ROOT = '/opt/android-sdk'
            ANDROID_HOME     = '/opt/android-sdk'
          }
          steps {
            checkout scm
            // assembleDebug 依赖 verifyDebugSigningKey，缺少共享 debug keystore
            // （docs/SIGNING.md）时会 fail closed，而 CI 绝不持有该密钥。单元测试、
            // lint 和全量源码编译覆盖同样的代码，只是不打包、不签名。
            dir('android') {
              sh './gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugSources --no-daemon'
            }
          }
          post {
            always {
              junit allowEmptyResults: true,
                    testResults: 'android/app/build/test-results/**/*.xml'
              archiveArtifacts allowEmptyArchive: true,
                    artifacts: 'android/app/build/reports/lint-results-debug.*'
            }
          }
        }

        stage('secrets') {
          agent { label 'linux' }
          steps {
            // gitleaks 需要完整历史，Job 的 SCM 配置必须关闭 shallow clone
            checkout scm
            sh '''
              set -eu
              docker run --rm -v "$PWD:/repo" -w /repo \
                zricethezav/gitleaks:latest \
                detect --source=/repo --redact --no-banner --exit-code 1
            '''
          }
        }

        stage('desktop') {
          when {
            beforeAgent true
            expression { return params.RUN_DESKTOP }
          }
          agent { label 'macos' }
          steps {
            checkout scm
            sh 'npm run desktop:assets:test'
            sh 'npm run desktop:test'
            sh 'npm run desktop:app'
          }
        }

      }
    }
  }
}
