import java.nio.file.Paths
import java.lang.reflect.*
import java.lang.ClassLoader
import java.util.List
import java.util.concurrent.ConcurrentHashMap
import com.sun.beans.WeakCache
import com.sun.beans.TypeResolver
import java.lang.invoke.MethodType

import org.ods.orchestration.util.PipelineUtil
import org.ods.orchestration.util.MROPipelineUtil
import org.ods.orchestration.util.Project
import org.ods.orchestration.usecase.OpenIssuesException
import org.ods.orchestration.InitStage
import org.ods.orchestration.BuildStage
import org.ods.orchestration.DeployStage
import org.ods.orchestration.TestStage
import org.ods.orchestration.ReleaseStage
import org.ods.orchestration.FinalizeStage
import org.ods.services.OpenShiftService
import org.ods.services.ServiceRegistry
import org.ods.services.GitService
import org.ods.util.Logger
import org.ods.util.ILogger
import org.ods.util.IPipelineSteps
import org.ods.util.PipelineSteps
import org.ods.util.UnirestConfig

@SuppressWarnings('AbcMetric')
def call(Map config) {
    def newName = "${env.JOB_NAME}/${env.BUILD_NUMBER}"
    UnirestConfig.init()
    def steps = new PipelineSteps(this)

    def debug = config.get('debug', false)
    ServiceRegistry.instance.add(Logger, new Logger(this, debug))
    ILogger logger = ServiceRegistry.instance.get(Logger)
  	logger.dumpCurrentStopwatchSize()
    def git = new GitService(steps, logger)

    def odsImageTag = config.odsImageTag
    if (!odsImageTag) {
        error "You must set 'odsImageTag' in the config map"
    }
    def versionedDevEnvsEnabled = config.get('versionedDevEnvs', false)
    def alwaysPullImage = !!config.get('alwaysPullImage', true)
    boolean startAgentEarly = config.get('startOrchestrationAgentOnInit', true)
    def startAgentStage = startAgentEarly ? MROPipelineUtil.PipelinePhases.INIT : null

    logger.debug ("Start agent stage: ${startAgentStage}")
    Project project = new Project(steps, logger)
    def repos = []

    logger.startClocked('orchestration-master-node')

  	try {
      node ('master') {
          logger.debugClocked('orchestration-master-node')
          // Clean workspace from previous runs
          [
              PipelineUtil.ARTIFACTS_BASE_DIR,
              PipelineUtil.SONARQUBE_BASE_DIR,
              PipelineUtil.XUNIT_DOCUMENTS_BASE_DIR,
              MROPipelineUtil.REPOS_BASE_DIR,
          ].each { name ->
              logger.debug("Cleaning workspace directory '${name}' from previous runs")
              Paths.get(env.WORKSPACE, name).toFile().deleteDir()
          }

          logger.startClocked('pipeline-git-releasemanager')
          def scmBranches = scm.branches
          def branch = scmBranches[0]?.name
          if (branch && !branch.startsWith('*/')) {
              scmBranches = [[name: "*/${branch}"]]
          }

          // checkout local branch
          git.checkout(
              scmBranches,
              [[$class: 'LocalBranch', localBranch: '**']],
              scm.userRemoteConfigs,
              scm.doGenerateSubmoduleConfigurations
              )
          logger.debugClocked('pipeline-git-releasemanager')

          def envs = Project.getBuildEnvironment(steps, debug, versionedDevEnvsEnabled)

          logger.startClocked('pod-template')
          withPodTemplate(odsImageTag, steps, alwaysPullImage) {
              logger.debugClocked('pod-template')
              withEnv (envs) {
                def result
                def cannotContinueAsHasOpenIssuesInClosingRelease = false
                try {
                    result = new InitStage(this, project, repos, startAgentStage).execute()
                } catch (OpenIssuesException ex) {
                    cannotContinueAsHasOpenIssuesInClosingRelease = true
                }
                if (cannotContinueAsHasOpenIssuesInClosingRelease) {
                    logger.warn('Cannot continue as it has open issues in the release.')
                    return
                }
                if (result) {
                    project = result.project
                    repos = result.repos
                    if (!startAgentStage) {
                        startAgentStage = result.startAgent
                    }
                } else {
                    logger.warn('Skip pipeline as no project/repos computed')
                    return
                }

                new BuildStage(this, project, repos, startAgentStage).execute()

                new DeployStage(this, project, repos, startAgentStage).execute()

                new TestStage(this, project, repos, startAgentStage).execute()

                new ReleaseStage(this, project, repos).execute()

                new FinalizeStage(this, project, repos).execute()
              }
          }
      }
    } finally {
      logger.debug('-- SHUTTING DOWN RM (.. incl classloader HACK!!!!!) --')
      logger.resetStopwatch()
      project.clear()
      ServiceRegistry.removeInstance()
      UnirestConfig.shutdown()
      project = null
      git = null
      repos = null
      steps = null
      // HACK!!!!!
      GroovyClassLoader classloader = (GroovyClassLoader)this.class.getClassLoader()
      logger.debug("${classloader} - parent ${classloader.getParent()}")
      logger.debug("Currently loaded classpath ${classloader.getClassPath()}")
      logger.debug("Currently loaded classes ${classloader.getLoadedClasses()}")
      classloader.clearCache()
      classloader.close()
      logger.debug("After closing: loaded classes ${classloader.getLoadedClasses().size()}")
        try {
            logger.debug("current (CleanGroovyCl): ${classloader}")
/*            Field loaderF = ClassLoader.class.getDeclaredField("classes")
            loaderF.setAccessible(true);
            logger.debug("current size ${loaderF.get(classloader).size()}")
            loaderF.get(classloader).clear()
            logger.debug("current cleared, now kicking parent CL")

            Field loaderParentF = ClassLoader.class.getDeclaredField("parent")
            loaderParentF.setAccessible(true);
*/
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
/*           modifiersField.setInt(loaderParentF, loaderParentF.getModifiers() & ~Modifier.FINAL);

            loaderParentF.set(classloader, null);
*/
            Field loaderName = ClassLoader.class.getDeclaredField("name")
            loaderName.setAccessible(true);
            modifiersField.setInt(loaderName, loaderName.getModifiers() & ~Modifier.FINAL);

            loaderName.set(classloader, "" + newName)
            String setname = loaderName.get(classloader)

            logger.debug("Current CL name set: ${setname}")
        } catch (Exception e) {
            logger.debug("e: ${e}")
        }

        try {
            logger.debug("starting hack cleanup")
            // https://github.com/mjiderhamn/classloader-leak-prevention/issues/125
            final Class<?> cacheClass = 
                this.class.getClassLoader().loadClass('java.io.ObjectStreamClass$Caches');

            if (cacheClass == null) { 
                logger.debug('could not find cache class')
                return; 
            } else {
                logger.debug("cache: ${cacheClass}")
            }

            Field modifiersField1 = Field.class.getDeclaredField("modifiers");
            modifiersField1.setAccessible(true);
            
            Field localDescs = cacheClass.getDeclaredField("localDescs")
            localDescs.setAccessible(true);
            modifiersField1.setInt(localDescs, localDescs.getModifiers() & ~Modifier.FINAL);

            clearIfConcurrentHashMap(localDescs.get(null), logger);

            Field reflectors = cacheClass.getDeclaredField("reflectors")
            reflectors.setAccessible(true);
            modifiersField1.setInt(reflectors, reflectors.getModifiers() & ~Modifier.FINAL);

            clearIfConcurrentHashMap(reflectors.get(null), logger);
        }
        catch (Exception e) {
            logger.debug("${e}")
        }

        try {
            logger.debug("starting hack cleanup2")
            // https://github.com/mjiderhamn/classloader-leak-prevention/issues/125
            final Class<?> cacheClass2 = 
                this.class.getClassLoader().loadClass('com.sun.beans.TypeResolver');

            if (cacheClass2 == null) { 
                logger.debug('could not find cache class')
                return; 
            } else {
                logger.debug("cache: ${cacheClass2}")
            }

            Field modifiersField2 = Field.class.getDeclaredField("modifiers");
            modifiersField2.setAccessible(true);
            
            Field localCaches = cacheClass2.getDeclaredField("CACHE")
            localCaches.setAccessible(true)
            modifiersField2.setInt(localCaches, localCaches.getModifiers() & ~Modifier.FINAL);

            WeakCache wCache = localCaches.get(null)
            wCache.clear()
        }
        catch (Exception e) {
            logger.debug("${e}")
        }

        try {
            logger.debug("starting hack cleanup3")
            // https://github.com/mjiderhamn/classloader-leak-prevention/issues/125
            final Class<?> cacheClass3 = 
                this.class.getClassLoader().loadClass('java.lang.invoke.MethodType');

            if (cacheClass3 == null) { 
                logger.debug('could not find cache class')
                return; 
            } else {
                logger.debug("cache: ${cacheClass3}")
            }

            Field modifiersField3 = Field.class.getDeclaredField("modifiers");
            modifiersField3.setAccessible(true);
            
            Field localCacheIntern = cacheClass3.getDeclaredField("internTable")
            localCacheIntern.setAccessible(true)
            modifiersField3.setInt(localCacheIntern, localCacheIntern.getModifiers() & ~Modifier.FINAL);

            Object internCache = localCacheIntern.get(null)
            logger.debug("got ${internCache}")

            Field localCacheInternMap = internCache.class.getDeclaredField("map")
            localCacheInternMap.setAccessible(true)
            modifiersField3.setInt(localCacheInternMap, localCacheInternMap.getModifiers() & ~Modifier.FINAL);

            clearIfConcurrentHashMap(localCacheInternMap.get(null), logger);
            logger.debug("cleared ..")
        }
        catch (Exception e) {
            logger.debug("${e}")
            hudson.Functions.printThrowable(e)
        }



/*        try {
            logger.debug("current parent (timingClassloader): ${classloader.getParent()}")
            if (classloader.getParent() != null) {
                Field loaderFP = ClassLoader.class.getDeclaredField("classes")
                loaderFP.setAccessible(true);
                logger.debug("current parent size ${loaderFP.get(classloader.getParent()).size()}")
                loaderFP.get(classloader.getParent()).clear()
                logger.debug("current parent cleared")
            }
        } catch (Exception e) {
            logger.debug("eParrent: ${e}")
        }
*/
    }
}

protected void clearIfConcurrentHashMap(Object object, Logger logger) {
    logger.debug("Clearing: ${object}")
    if (!(object instanceof ConcurrentHashMap)) { return; }
    ConcurrentHashMap<?,?> map = (ConcurrentHashMap<?,?>) object;
    int nbOfEntries=map.size();
    map.clear();
    logger.info("Detected and fixed leak situation for java.io.ObjectStreamClass ("+nbOfEntries+" entries were flushed).");
}

@SuppressWarnings('GStringExpressionWithinString')
private withPodTemplate(String odsImageTag, IPipelineSteps steps, boolean alwaysPullImage, Closure block) {
    ILogger logger = ServiceRegistry.instance.get(Logger)
    def dockerRegistry = steps.env.DOCKER_REGISTRY ?: 'docker-registry.default.svc:5000'
    def podLabel = "mro-jenkins-agent-${env.BUILD_NUMBER}"
    def odsNamespace = env.ODS_NAMESPACE ?: 'ods'
    if (!OpenShiftService.envExists(steps, odsNamespace)) {
        logger.warn("Could not find ods namespace '${odsNamespace}' - defaulting to legacy namespace: 'cd'!\r" +
            "Please configure 'env.ODS_NAMESPACE' to point to the ODS Openshift namespace")
        odsNamespace = 'cd'
    }
    podTemplate(
        label: podLabel,
        cloud: 'openshift',
        containers: [
            containerTemplate(
                name: 'jnlp',
                image: "${dockerRegistry}/${odsNamespace}/jenkins-agent-base:${odsImageTag}",
                workingDir: '/tmp',
                resourceRequestMemory: '512Mi',
                resourceLimitMemory: '1Gi',
                resourceRequestCpu: '200m',
                resourceLimitCpu: '1',
                alwaysPullImage: "${alwaysPullImage}",
                args: '${computer.jnlpmac} ${computer.name}',
                envVars: []
            )
        ],
        volumes: [],
        serviceAccount: 'jenkins',
        idleMinutes: 10,
    ) {
        logger.startClocked('ods-mro-pipeline')
        try {
            block()
        } finally {
            logger.infoClocked('ods-mro-pipeline', '**** ENDED orchestration pipeline ****')
        }
    }
}

return this

