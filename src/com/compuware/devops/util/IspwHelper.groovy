package com.compuware.devops.util

import groovy.json.JsonSlurper
import jenkins.plugins.http_request.*
import com.compuware.devops.util.TaskInfo

class IspwHelper implements Serializable 
{
    def steps

    def String ispwUrl
    def String ispwRuntime
    def String ispwApplication
    def String ispwRelease
    def String ispwContainer
    def String ispwContainerType    
    def String applicationPathNum
    def String ispwOwner

    def String mfSourceFolder

    def String hciConnId
    def String hciTokenId

    //String ispwUrl, String ispwRuntime, String ispwRelease, String ispwContainer)

    IspwHelper(steps, config) 
    {

        this.steps              = steps
        this.ispwUrl            = config.ispwUrl
        this.ispwRuntime        = config.ispwRuntime
        this.ispwApplication    = config.ispwApplication
        this.ispwRelease        = config.ispwRelease        
        this.ispwContainer      = config.ispwContainer
        this.ispwContainerType  = config.ispwContainerType
        this.ispwOwner          = config.ispwOwner
        this.applicationPathNum = config.applicationPathNum

        this.mfSourceFolder     = config.mfSourceFolder

        this.hciConnId          = config.hciConnId
        this.hciTokenId         = config.hciTokenId
    }

    def downloadSources()
    {
        steps.checkout([
            $class:             'IspwContainerConfiguration', 
            componentType:      '',                                 // optional filter for component types in ISPW
            connectionId:       "${hciConnId}",     
            credentialsId:      "${hciTokenId}",      
            containerName:      "${ispwContainer}",   
            containerType:      "${ispwContainerType}",     // 0-Assignment 1-Release 2-Set
            ispwDownloadAll:    false,                              // false will not download files that exist in the workspace and haven't previous changed
            serverConfig:       '',                                 // ISPW runtime config.  if blank ISPW will use the default runtime config
            serverLevel:        ''                                  // level to download the components from
        ])                           
    }

    def downloadCopyBooks(String workspace)
    {
        JclSkeleton jclSkeleton = new JclSkeleton(steps, workspace, ispwApplication, applicationPathNum)

        jclSkeleton.initialize()

        def copyBookList = referencedCopyBooks(workspace)  

        if(copyBookList.size() > 0)       
        {
            // Get a string with JCL to create a PDS with referenced Copybooks
            def pdsDatasetName  = 'HDDRXM0.DEVOPS.ISPW.COPY.PDS'   

            def processJcl      = jclSkeleton.createIebcopyCopyBooksJcl(pdsDatasetName, copyBookList)

            steps.echo "Will Submit \n" + processJcl

            // Submit the JCL created to create a PDS with Copybooks
            steps.topazSubmitFreeFormJcl( 
                connectionId:       "${hciConnId}", 
                credentialsId:      "${hciTokenId}", 
                jcl:                processJcl, 
                maxConditionCode:   '4'
            )
                        
            // Download the PDS generated
            steps.checkout([
                $class:         'PdsConfiguration', 
                connectionId:   "${hciConnId}",
                credentialsId:  "${hciTokenId}",
                fileExtension:  'cpy',
                filterPattern:  "${pdsDatasetName}",
                targetFolder:   "${ispwApplication}/${mfSourceFolder}"
            ])
                                                                        
            // Delete the downloaded Dataset
            processJcl = jclSkeleton.createDeleteTempDsn(pdsDatasetName)

            steps.topazSubmitFreeFormJcl(
                connectionId:       "${hciConnId}",
                credentialsId:      "${hciTokenId}",
                jcl:                processJcl,
                maxConditionCode:   '4'
            )
        }
        else
        {
            steps.echo "No Copy Books to download"
        }
    }


/* 
    Determine all assignments in the current container 
*/
    def ArrayList getAssigmentList(String cesToken, String level)
    {
        def returnList  = []

        /* Get the list of taskIds in the current set */
        def taskIds     = getSetTaskIdList(cesToken, level)

        /* Get all tasks in the corresponding release */
        def response = steps.httpRequest(
            url:                        "${ispwUrl}/ispw/${ispwRuntime}/releases/${ispwRelease}/tasks",
            consoleLogResponseBody:     false, 
            customHeaders:              [[
                                        maskValue:  true, 
                                        name:       'authorization', 
                                        value:      "${cesToken}"
                                        ]]
            )

        def jsonSlurper = new JsonSlurper()
        def resp        = jsonSlurper.parseText(response.getContent())
        response        = null
        jsonSlurper     = null

        if(resp.message != null)
        {
            steps.echo "Resp: " + resp.message
            error
        }
        else
        {
            /* Compare the taskIds from the set to all tasks in the release */
            /* Where they match, determine the assignment and add it to the list of assignments */
            def taskList = resp.tasks

            taskList.each
            {
                if(taskIds.contains(it.taskId))
                {
                    /* Add assignment only if it not already in the list */
                    if(!(returnList.contains(it.container)))
                    {
                        returnList.add(it.container)        
                    }
                }
            }
        }

        return returnList    

    }

/* 
    Build and return a list of taskIds in the current container, that belong to the desired level
*/
    def ArrayList getSetTaskIdList(String cesToken, String level)
    {
        def returnList  = []

        def response = steps.httpRequest(
            url:                        "${ispwUrl}/ispw/${ispwRuntime}/sets/${ispwContainer}/tasks",
            httpMode:                   'GET',
            consoleLogResponseBody:     false,
            customHeaders:              [[
                                        maskValue:  true, 
                                        name:       'authorization', 
                                        value:      "${cesToken}"
                                        ]]
            )

        def jsonSlurper = new JsonSlurper()
        def resp        = jsonSlurper.parseText(response.getContent())
        response        = null
        jsonSlurper     = null

        if(resp.message != null)
        {
            steps.echo "Resp: " + resp.message
            error
        }
        else
        {
            def taskList = resp.tasks

            taskList.each
            {
                /* Add taskId only if the component is a COBOL program and is on the desired level */
                if(it.moduleType == 'COB' && it.level == level)
                {
                    returnList.add(it.taskId)
                }
            }
        }

        return returnList
    
    }

/* 
    Receive a response from an "Get Tasks in Set"-httpRequest and build and return a list of TaskAsset Objects that belong to the desired level
*/
    def ArrayList getSetTaskList(ResponseContentSupplier response, String level)
    {

        def jsonSlurper         = new JsonSlurper()

        int ispwTaskCounter     = 0

        def returnList  = []

        def resp = jsonSlurper.parseText(response.getContent())

        if(resp.message != null)
        {
            steps.echo "Resp: " + resp.message
            error
        }
        else
        {
            def taskList = resp.tasks

            taskList.each
            {
                if(it.moduleType == 'COB' && it.level == level)
                {
                    returnList[ispwTaskCounter]             = new TaskInfo()                    
                    returnList[ispwTaskCounter].programName = it.moduleName
                    returnList[ispwTaskCounter].ispwTaskId  = it.taskId

                    ispwTaskCounter++
                }
            }
        }

        return returnList
    
    }

/* 
    Receive a response from an "Get Tasks in Set"-httpRequest and return the List of Releases
*/
    def ArrayList getSetRelease(ResponseContentSupplier response)
    {
        def jsonSlurper = new JsonSlurper()
        def returnList  = []
        def resp        = jsonSlurper.parseText(response.getContent())

        if(resp.message != null)
        {
            echo "Resp: " + resp.message
            error
        }
        else
        {
            def taskList = resp.tasks

            taskList.each
            {
                if(it.moduleType == 'COB')
                {
                    returnList.add(it.release)
                }
            }
        }

        return returnList
    
    }

/*
    Receive a list of TaskInfo Objects, the response of an "List tasks of a Release"-httpRequest to build and return a List of TaskInfo Objects
    that contain the base and internal version
*/
    def setTaskVersions(ArrayList tasks, ResponseContentSupplier response, String level)
    {
        def jsonSlurper = new JsonSlurper()

        def resp        = jsonSlurper.parseText(response.getContent())

        def returnList  = []

        if(resp.message != null)
        {
            echo "Resp: " + resp.message
            error
        }
        else
        {
            def taskList = resp.tasks

            for(int i = 0; i < tasks.size(); i++)
            {
                taskList.each
                {
                    if(it.taskId == tasks[i].ispwTaskId && it.level == level)
                    {
                        returnList[i]               = new TaskInfo()
                        returnList[i].programName   = tasks[i].programName
                        returnList[i].baseVersion   = it.baseVersion
                        returnList[i].targetVersion = it.internalVersion
                        returnList[i].ispwTaskId    = tasks[i].ispwTaskId
                    }
                }
            }
        }

        return returnList

    }

    def List referencedCopyBooks(String workspace) 
    {

        steps.echo "Get all .cbl in current workspace"
        
        // findFiles method requires the "Pipeline Utilities Plugin"
        // Get all Cobol Sources in the MF_Source folder into an array 
        def listOfSources   = steps.findFiles(glob: "**/${ispwApplication}/${mfSourceFolder}/*.cbl")
        def listOfCopybooks = []
        def lines           = []
        def cbook           = /\bCOPY\b/
        def tokenItem       = ''
        def seventhChar     = ''
        def lineToken       = ''

        // Define a empty array for the list of programs
        listOfSources.each 
        {
            steps.echo "Scanning Program: ${it}"
            def cpyFile = "${workspace}\\${it}"

            File file = new File(cpyFile)

            if (file.exists()) 
            {
                lines = file.readLines().findAll({book -> book =~ /$cbook/})

                lines.each 
                {
                    lineToken   = it.toString().tokenize()
                    seventhChar = ""

                    if (lineToken.get(0).toString().length() >= 7) 
                    {
                        seventhChar = lineToken.get(0).toString()[6]
                    }
                        
                    for(int i=0;i<lineToken.size();i++) 
                    {
                        tokenItem = lineToken.get(i).toString()

                        if (tokenItem == "COPY" && seventhChar != "*" ) 
                        {
                            steps.echo "Copybook: ${lineToken.get(i+1)}"
                            tokenItem = lineToken.get(i+1).toString()
        
                            if (tokenItem.endsWith(".")) 
                            {
                                listOfCopybooks.add(tokenItem.substring(0,tokenItem.size()-1))
                            }
                            else 
                            {
                                listOfCopybooks.add(tokenItem)
                            }
                                
                        i = lineToken.size()
                        }
                    }    
                }
            }
        }

        return listOfCopybooks

    }        
}