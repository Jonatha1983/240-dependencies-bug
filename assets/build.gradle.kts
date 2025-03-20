import org.siouan.frontendgradleplugin.infrastructure.gradle.RunNpmTaskType
import org.siouan.frontendgradleplugin.infrastructure.gradle.RunYarnTaskType
import java.nio.file.Files
import java.nio.file.Path

fun properties(key: String): Provider<String> = providers.gradleProperty(key)

plugins {
  id("org.siouan.frontend-jdk21") version "10.0.0"
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

@Suppress("unused")
val webAzdConfiguration: Configuration by configurations.creating {
  isCanBeConsumed = true
  isCanBeResolved = false
}

frontend {
  nodeVersion.set("21.7.1")


  // DON'T use the `build` directory if it also the output of the `react-scripts`
  // otherwise it causes 'Error: write EPIPE' because `node` location is also
  // in the output folder of react-scripts.
  // This projects set up the BUILD_PATH=./build/react-build/ for react-scripts
  nodeInstallDirectory.set(project.layout.buildDirectory.dir("node"))

  assembleScript.set("run build") // "build" script in package.json
  // not implemented yet ?
  //   cleanScript.set("run clean")
  //   checkScript.set("run check")
  verboseModeEnabled.set(false)
}


val port = providers.provider { // PORT might be is in package.json "/scripts/start" value
  // dumb solution to extract the port if possible
  val defaultPort = 3000
  file("package.json").useLines { lines ->
    val startScriptRegex = Regex("\"start\"\\s?:\\s?\"[^\"]+\"")
    lines.filter { startScriptRegex.containsMatchIn(it) }.map { Regex("\".*PORT=(\\d+).*\"").find(it)?.groups?.get(1)?.value }.map { it?.toInt() }.firstOrNull() ?: defaultPort
  }
}


/**
 * Note for future me:
 * Build cache doc: https://docs.gradle.org/current/userguide/build_cache.html
 * Debug task cacheability: -Dorg.gradle.caching.debug=true
 *
 * Disabling `outputs.cacheIf { true }` as it somehow breaks up-to-date check
 */
tasks {
  val updateBrowserList by registering(RunNpmTaskType::class) {
    group = "frontend" // Note npx is deprecated, use `npm exec` instead
    // https://docs.npmjs.com/cli/v10/commands/npm-exec#npx-vs-npm-exec
    description = "npx update-browserslist-db@latest" // Browserslist: caniuse-lite is outdated. Please run:
    //   npx update-browserslist-db@latest
    //   Why you should do it regularly: https://github.com/browserslist/update-db#readme
    args.set("exec -- update-browserslist-db@latest")

    onlyIf {
      gradle.startParameter.taskNames.run {
        contains("assemble") || contains("updateBrowserList")
      }
    }
  }

  installFrontend {
    dependsOn(updateBrowserList)
  }

  val runYarnInstall by registering(RunYarnTaskType::class) {
    dependsOn(installFrontend) // this task is being run when the `clean` task is invoked, making this one fail
    // because `Cytoscape-assets/build/node/bin/yarn` has been removed.
    onlyIf {
      gradle.startParameter.taskNames.run {
        !contains("clean")
      }
    }
    group = "frontend"
    description = "Runs the yarn install command to fetch packages described in `package.json`"

    inputs.files("package.json")
    outputs.files("yarn.lock")
    args.set("install")
  }

  val copyCytoscapeAssets by registering(Copy::class) {
    dependsOn(runYarnInstall)
    group = "frontend"
    description = "copy necessary files to run the embedded app"

    // Copy from cytoscape distribution
    from(project.layout.projectDirectory.dir("node_modules").dir("cytoscape").dir("dist")) {
      include("cytoscape.min.js", "cytoscape.esm.min.mjs")
    }

    into(project.layout.buildDirectory.dir("assets/cytoscape"))

    inputs.dir(project.layout.projectDirectory.dir("node_modules").dir("cytoscape").dir("dist"))
    outputs.dir(project.layout.buildDirectory.dir("assets/cytoscape"))
  }

  val copyCytoscapeDagreAssets by registering(Copy::class) {
    dependsOn(runYarnInstall)
    group = "frontend"
    description = "copy necessary files to run the embedded app"


    // Copy cytoscape-dagre integration plugin
    from(project.layout.projectDirectory.dir("node_modules").dir("cytoscape-dagre")) {
      include("cytoscape-dagre.js")  // Include the main JavaScript file for cytoscape-dagre
    }

    into(project.layout.buildDirectory.dir("assets/cytoscape-dagre"))


    inputs.dir(project.layout.projectDirectory.dir("node_modules").dir("cytoscape-dagre"))
    outputs.dir(project.layout.buildDirectory.dir("assets/cytoscape-dagre"))
  }

  installFrontend {
    finalizedBy(runYarnInstall)
    inputs.files("package.json", ".yarnrc.yml", "yarn.lock")
    outputs.dir(project.layout.projectDirectory.dir("node_modules"))

    val lockFilePath = layout.projectDirectory.file("yarn.lock").asFile.path

    val retainedMetadataFileNames = buildSet {
      add(layout.projectDirectory.file("package.json").asFile.path)
      if (Files.exists(Path.of(lockFilePath))) {
        add(lockFilePath)
      }
    }

    inputs.files(retainedMetadataFileNames).withPropertyName("metadataFiles")
    outputs.dir(layout.projectDirectory.dir("node_modules")).withPropertyName("nodeModulesDirectory")
  }

  assembleFrontend {
    dependsOn(copyCytoscapeAssets, copyCytoscapeDagreAssets)
    inputs.files("package.json", "src", "public")
    outputs.dirs(project.layout.buildDirectory.dir("react-build"))
    environmentVariables.put("GENERATE_SOURCEMAP", "false")
  }


  val stopYarnServer by registering(Exec::class) {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")

    if (isWindows) { // Windows-specific command to kill the process
      commandLine("cmd", "/c", "for /f \"tokens=5\" %a in ('netstat -ano ^| findstr :${port.get()}') do taskkill /F /PID %a")
    } else { // Unix-like specific command to kill the process
      commandLine("bash", "-c", "kill $(lsof -t -i :${port.get()})")
    }
    onlyIf {
      false
    }

    //onlyIf {
    //  val output = ByteArrayOutputStream()
    //  exec {
    //    isIgnoreExitValue = true
    //    if (isWindows) {
    //      commandLine("cmd", "/c", "netstat -ano | findstr :${port.get()}")
    //    } else {
    //      commandLine("lsof", "-t", "-i", ":${port.get()}")
    //    }
    //    standardOutput = output
    //  }
    //
    //  return@onlyIf output.toString().isNotEmpty()
    //}
  }

  register<RunYarnTaskType>("runYarnStart") {
    dependsOn(installFrontend, stopYarnServer)
    group = "frontend"
    description = "Starts yarn, you'll need to actively kill the server after (`kill $(lsof -t -i :${port.get()})`)"

    args.set("run start")

    //doFirst {
    //  logger.warn(
    //    """
    //            Unfortunately node won't be killed on ctrl+c, you to actively kill it:
    //                $ kill ${'$'}(lsof -t -i :${port.get()})
    //
    //            An alternative would be (from the project's root folder):
    //                $ yarn --cwd cytoscape-assets start
    //
    //            """.trimIndent()
    //  )
    //}
    //doLast {
    //  logger.warn("""test after""")
    //}
  }

  register<YarnProxy>("yarn") {
    group = "frontend"
    description = "Run yarn script, e.g. for 'yarn add -D eslint', you can use './gradlew yarn --command=\"add -D eslint\"'"
  }

  register<Delete>("cleanFrontend") {
    delete(
      layout.buildDirectory.dir("assets/cytoscape"),
      layout.buildDirectory.dir("assets/cytoscape-dagre"),
      layout.buildDirectory.dir("node"),
      layout.buildDirectory.dir("react-build"),
      layout.buildDirectory.dir("tmp/frontend-tmp"),
      layout.projectDirectory.dir(".yarn/cache"),
      layout.projectDirectory.dir("node_modules"),
      layout.projectDirectory.file(".yarn/install-state.gz")
    )
  }

  clean { // It seems that these tasks are called upon clean
    listOf(
      installNode,
      installPackageManager,
      installFrontend,
    ).forEach { task ->
      task.get().onlyIf {
        gradle.startParameter.taskNames.run {
          none { it.endsWith("clean") }
        }
      }
    }

    dependsOn(stopYarnServer, "cleanFrontend")

  }

  artifacts {
    add("webAzdConfiguration", assembleFrontend) {
      builtBy(assembleFrontend)
    }
    add("webAzdConfiguration", copyCytoscapeAssets.get().outputs.files.singleFile) {
      builtBy(copyCytoscapeAssets)
    }
    add("webAzdConfiguration", copyCytoscapeDagreAssets.get().outputs.files.singleFile) {
      builtBy(copyCytoscapeDagreAssets)
    }


  }
}

open class YarnProxy @Inject constructor(objectFactory: ObjectFactory, execOperations: ExecOperations) : RunYarnTaskType(objectFactory, execOperations) {
  @set:Option(option = "command", description = "The command to pass to yarn")
  @get:Input
  var yarnArgs: String = ""
    set(value) {
      args.set(value)
    }

  init {
    args.set(yarnArgs)
  }
}

