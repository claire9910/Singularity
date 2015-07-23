Controller = require './Controller'

DeployDetails          = require '../models/DeployDetails'
RequestHistoricalTasks = require '../collections/RequestHistoricalTasks'
RequestTasks           = require '../collections/RequestTasks'

DeployDetailView       = require '../views/deploy'
ExpandableTableSubview = require '../views/expandableTableSubview'
SimpleSubview          = require '../views/simpleSubview'

class DeployDetailController extends Controller

  templates:
    header:             require '../templates/deployDetail/deployHeader'
    info:               require '../templates/deployDetail/deployInfo'
    taskHistory:        require '../templates/deployDetail/deployTasks'
    activeTasks:        require '../templates/deployDetail/activeTasks'

  initialize: ({@requestId, @deployId}) ->
    #
    # Data stuff
    #
    @models.deploy = new DeployDetails
      deployId: @deployId
      requestId: @requestId

    @collections.taskHistory = new RequestHistoricalTasks [],
      requestId: @requestId

    @collections.activeTasks = new RequestTasks [],
        requestId: @requestId
        state:    'active'

    #
    # Subviews
    #
    @subviews.header = new SimpleSubview
      model:      @models.deploy
      template:   @templates.header

    @subviews.info = new SimpleSubview
      model:      @models.deploy
      template:   @templates.info

    @subviews.taskHistory = new ExpandableTableSubview
      collection: @collections.taskHistory
      template:   @templates.taskHistory

    @subviews.activeTasks = new ExpandableTableSubview
      collection: @collections.activeTasks
      template:   @templates.activeTasks

    @refresh()
    @setView new DeployDetailView _.extend {@requestId, @deployId, @subviews},
      model: @models.deploy

    app.showView @view

  refresh: ->
    requestFetch = @models.deploy.fetch()

    @collections.taskHistory.atATime = 999999
    promise = @collections.taskHistory.fetch()
    promise.error =>
        @ignore404
    promise.done =>
        filtered = @collections.taskHistory.getTasksForDeploy(@deployId)
        @collections.taskHistory.atATime = 5
        if filtered.length
            @collections.taskHistory.reset(filtered)
        else
            @collections.taskHistory.reset()

    @collections.taskHistory.atATime = 999999
    promise = @collections.activeTasks.fetch()
    promise.error =>
        @ignore404
    promise.done =>
        console.log @collections.activeTasks
        filtered = @collections.activeTasks.getTasksForDeploy(@deployId)
        console.log filtered
        @collections.taskHistory.atATime = 5
        if filtered.length
            @collections.activeTasks.reset(filtered)
        else
            @collections.activeTasks.reset()
            console.log @collections.activeTasks


module.exports = DeployDetailController
