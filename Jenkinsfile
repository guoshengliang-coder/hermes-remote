// 由 .github/workflows/ci.yml、gateway-oci.yml、sast.yml 翻译而来的 Jenkins 流水线。
//
// 与 GitHub Actions 上的设计保持一致：普通 CI 是刻意不授权的 —— 它拿不到
// 规范 debug 签名密钥，也拿不到任何发布密钥，因此只跑无需签名材料的检查。
// 真正打包发布的流程（对应 android-release.yml）独立成单独的 Job，绝不出现在这里。
//
// agent 标签：
//   linux — Ubuntu 构建机，提供 JDK 21 / Node 22 / Docker / Android SDK
//   macos — macOS 构建机，仅 desktop 阶段需要（Apple 工具链无法在其他系统运行）
//
// 简化说明（相对原 GitHub workflow）：
//   - sast.yml 原本还带每周一凌晨的定时扫描；这里没有单独复刻那个 cron，
//     因为 Multibranch 会给 67 个分支各自建一份 Jenkinsfile 副本，每分支
//     单独定时扫描既浪费又没有对应收益。semgrep 现在跟其它检查一样，
//     每次触发构建（push / MR）都会跑一遍，覆盖面不小于原来的按周定时。
//   - gateway-oci.yml 原本只在改动 gateway/connector/protocol 等特定路径时
//     才触发；这里没有做路径过滤，每次构建都跑。该 stage 本身很快，
//     换来配置更简单，计算成本可接受。
//
// 资源约束（2026-09-05 事故后加）：构建机 .137 同时跑着一个 gateway，那次
// Jenkins 把 4 核压到 load 57，gateway 完全无响应、SSH 握手都失败。现在
// 构建机上建了 ci.slice（CPUQuota=200%、MemoryMax=4.5G）作为内核级总预算，
// jenkins.service 归属其下。但 docker 容器默认跑在 /system.slice/docker-*.scope，
// 与 jenkins.service 平级，不受该配额约束 —— 所以下面每个 docker run 都必须
// 显式带 --cgroup-parent=ci.slice，否则容器会绕过总预算。--cpus/--memory 是
// 容器自身的二级上限，防止单个容器吃光整份 CI 预算。

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
                --cgroup-parent=ci.slice --cpus=1 --memory=512m \
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
                --cgroup-parent=ci.slice --cpus=1 --memory=1g \
                zricethezav/gitleaks:latest \
                detect --source=/repo --redact --no-banner --exit-code 1
            '''
          }
        }

        stage('gateway-oci') {
          agent { label 'linux' }
          steps {
            checkout scm
            sh '''
              set -eu
              ./scripts/test-gateway-image.sh
              ./scripts/package-gateway-bundle.sh outputs/gateway-bundle
            '''
          }
          post {
            always {
              archiveArtifacts allowEmptyArchive: true,
                    artifacts: 'outputs/gateway-bundle/**'
            }
          }
        }

        stage('sast') {
          agent { label 'linux' }
          steps {
            checkout scm
            // 官方 Semgrep CE 镜像，版本/摘要与 sast.yml 一致
            sh '''
              set -eu
              docker run --rm -v "$PWD:/repo" -w /repo \
                --cgroup-parent=ci.slice --cpus=1 --memory=2g \
                --security-opt=no-new-privileges \
                -e SEMGREP_SEND_METRICS=off \
                --entrypoint semgrep \
                semgrep/semgrep:1.176.0@sha256:12672acdb0949e19f9f6a4c2b288edd0b404f268f0ca7738a2c06f372f50362e \
                scan --config p/default --error --metrics off --timeout 60 --max-target-bytes 1000000 .
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
