from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
net_path = root / "app/src/main/java/ru/evrasia/research/NetworkDebuggerActivity.kt"
main_path = root / "app/src/main/java/ru/evrasia/research/WebResearchV10Activity.kt"
gradle_path = root / "app/build.gradle.kts"

net = net_path.read_text(encoding="utf-8")
if "import android.app.Dialog\n" not in net:
    net = net.replace("import android.app.AlertDialog\n", "import android.app.AlertDialog\nimport android.app.Dialog\n", 1)

bad_endpoint = 'append("\n").append(e.optString("url",""))'
good_endpoint = 'append("\\n").append(e.optString("url",""))'
if bad_endpoint not in net:
    raise SystemExit("endpoint newline pattern not found")
net = net.replace(bad_endpoint, good_endpoint, 1)

bad_realtime = '''            val displayLine="${if(e.has("time"))listTime(e.optLong("time")) else "--:--:--.---"}  $direction${if(data.isNotBlank())"
$data" else ""}"
            copyText.append(displayLine).append("

")'''
good_realtime = '''            val displayLine="${if(e.has("time"))listTime(e.optLong("time")) else "--:--:--.---"}  $direction${if(data.isNotBlank())"\\n$data" else ""}"
            copyText.append(displayLine).append("\\n\\n")'''
if bad_realtime not in net:
    raise SystemExit("realtime newline pattern not found")
net = net.replace(bad_realtime, good_realtime, 1)
net_path.write_text(net, encoding="utf-8")

main = main_path.read_text(encoding="utf-8")
bad_about = '''                text = "Версия приложения: $version
Релиз: $version

Мобильный браузер для исследования сетевого взаимодействия сайтов."'''
good_about = '                text = "Версия приложения: $version\\nРелиз: $version\\n\\nМобильный браузер для исследования сетевого взаимодействия сайтов."'
if bad_about not in main:
    raise SystemExit("about newline pattern not found")
main = main.replace(bad_about, good_about, 1)
main_path.write_text(main, encoding="utf-8")

gradle_path.write_text('''plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val stableStore = rootProject.file("webresearch.keystore")

android {
    namespace = "ru.evrasia.research"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.evrasia.research"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = System.getenv("WEB_RESEARCH_VERSION") ?: "dev"
    }
    signingConfigs {
        if (stableStore.exists()) {
            create("stable") {
                storeFile = stableStore
                storePassword = "webresearch"
                keyAlias = "webresearch"
                keyPassword = "webresearch"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (stableStore.exists()) signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
''', encoding="utf-8")

Path(__file__).unlink()
subprocess.run(["git", "config", "user.name", "github-actions[bot]"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "rm", "-r", "--cached", "--ignore-unmatch", ".gradle", "webresearch.keystore"], cwd=root, check=True)
subprocess.run(["git", "add", str(net_path.relative_to(root)), str(main_path.relative_to(root)), str(gradle_path.relative_to(root))], cwd=root, check=True)
subprocess.run(["git", "add", "-u", "scripts"], cwd=root, check=True)
subprocess.run(["git", "commit", "-m", "fix: repair v79 kotlin materialization [skip ci]"], cwd=root, check=True)
subprocess.run(["git", "push", "origin", "HEAD:main"], cwd=root, check=True)
print("v79 Kotlin literals repaired")
