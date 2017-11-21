/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs.impl

import com.google.common.collect.HashMultiset
import com.google.common.collect.Multiset
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationAdapter
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryAdapter
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndex
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusListener
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.VcsApplicationSettings
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.ex.LineStatusTracker
import com.intellij.openapi.vcs.ex.PartialLocalLineStatusTracker
import com.intellij.openapi.vcs.ex.SimpleLocalLineStatusTracker
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFilePropertyEvent
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.GuiUtils
import com.intellij.util.concurrency.QueueProcessorRemovePartner
import com.intellij.util.containers.HashMap
import com.intellij.vcsUtil.VcsUtil
import org.jetbrains.annotations.CalledInAwt
import org.jetbrains.annotations.NonNls
import java.nio.charset.Charset

class LineStatusTrackerManager(
  private val project: Project,
  private val application: Application,
  private val statusProvider: VcsBaseContentProvider,
  private val changeListManager: ChangeListManagerImpl,
  private val fileDocumentManager: FileDocumentManager,
  @Suppress("UNUSED_PARAMETER") makeSureIndexIsInitializedFirst: DirectoryIndex
) : ProjectComponent, LineStatusTrackerManagerI {

  private val LOCK = Any()
  private val disposable: Disposable = Disposer.newDisposable()
  private var isDisposed = false

  private val trackers = HashMap<Document, TrackerData>()
  private val forcedDocuments = HashMap<Document, Multiset<Any>>()

  private val partialChangeListsEnabled = Registry.`is`("vcs.enable.partial.changelists")
  private val documentsInDefaultChangeList = HashSet<Document>()

  private val queue: QueueProcessorRemovePartner<Document, BaseRevisionLoader> = QueueProcessorRemovePartner(project)
  private var ourLoadCounter: Long = 0

  companion object {
    private val LOG = Logger.getInstance("#com.intellij.openapi.vcs.impl.LineStatusTrackerManager")

    @JvmStatic
    fun getInstance(project: Project): LineStatusTrackerManagerI {
      return project.getComponent(LineStatusTrackerManagerI::class.java)
    }
  }

  override fun initComponent() {
    StartupManager.getInstance(project).registerPreStartupActivity {
      if (isDisposed) return@registerPreStartupActivity

      application.addApplicationListener(MyApplicationListener(), disposable)

      val busConnection = project.messageBus.connect(disposable)
      busConnection.subscribe(LineStatusTrackerSettingListener.TOPIC, MyLineStatusTrackerSettingListener())

      val fsManager = FileStatusManager.getInstance(project)
      fsManager.addFileStatusListener(MyFileStatusListener(), disposable)

      val editorFactory = EditorFactory.getInstance()
      editorFactory.addEditorFactoryListener(MyEditorFactoryListener(), disposable)
      if (partialChangeListsEnabled) editorFactory.eventMulticaster.addDocumentListener(MyDocumentListener(), disposable)

      val virtualFileManager = VirtualFileManager.getInstance()
      virtualFileManager.addVirtualFileListener(MyVirtualFileListener(), disposable)
    }
  }

  override fun disposeComponent() {
    isDisposed = true
    Disposer.dispose(disposable)

    synchronized(LOCK) {
      for ((document, multiset) in forcedDocuments) {
        for (requester in multiset.elementSet()) {
          warn("Tracker for is being held on dispose by $requester", document)
        }
      }
      forcedDocuments.clear()

      for (data in trackers.values) {
        unregisterTrackerInCLM(data.tracker)
        data.tracker.release()
      }
      trackers.clear()

      queue.clear()
    }
  }

  @NonNls
  override fun getComponentName(): String {
    return "LineStatusTrackerManager"
  }

  override fun getLineStatusTracker(document: Document): LineStatusTracker<*>? {
    synchronized(LOCK) {
      if (isDisposed) return null
      return trackers[document]?.tracker
    }
  }


  @CalledInAwt
  override fun requestTrackerFor(document: Document, requester: Any) {
    synchronized(LOCK) {
      val multiset = forcedDocuments.computeIfAbsent(document) { HashMultiset.create<Any>() }
      multiset.add(requester)

      if (trackers[document] == null) {
        val virtualFile = fileDocumentManager.getFile(document) ?: return
        installTracker(virtualFile, document)
      }
    }
  }

  @CalledInAwt
  override fun releaseTrackerFor(document: Document, requester: Any) {
    synchronized(LOCK) {
      val multiset = forcedDocuments[document]
      if (multiset == null || !multiset.contains(requester)) {
        warn("Tracker release underflow by $requester", document)
        return
      }

      multiset.remove(requester)

      if (multiset.isEmpty()) {
        forcedDocuments.remove(document)
        checkIfTrackerCanBeReleased(document)
      }
    }
  }

  @CalledInAwt
  private fun checkIfTrackerCanBeReleased(document: Document) {
    synchronized(LOCK) {
      val data = trackers[document] ?: return

      if (forcedDocuments.containsKey(document)) return

      if (data.tracker is PartialLocalLineStatusTracker) {
        val hasPartialChanges = data.tracker.getAffectedChangeListsIds().size > 1
        val isLoading = queue.containsKey(document)
        if (hasPartialChanges || isLoading) return
      }

      releaseTracker(document)
    }
  }


  @CalledInAwt
  private fun onEverythingChanged() {
    synchronized(LOCK) {
      if (isDisposed) return
      log("onEverythingChanged", null)

      val files = HashSet<VirtualFile>()

      for (data in trackers.values) {
        files.add(data.tracker.virtualFile)
      }
      for (document in forcedDocuments.keys) {
        val file = fileDocumentManager.getFile(document)
        if (file != null) files.add(file)
      }

      for (file in files) {
        onFileChanged(file)
      }
    }
  }

  @CalledInAwt
  private fun onFileChanged(virtualFile: VirtualFile) {
    val document = fileDocumentManager.getCachedDocument(virtualFile) ?: return

    synchronized(LOCK) {
      if (isDisposed) return
      log("onFileChanged", virtualFile)
      val tracker = trackers[document]?.tracker

      if (tracker == null) {
        if (forcedDocuments.containsKey(document)) {
          installTracker(virtualFile, document)
        }
      }
      else {
        val isPartialTrackerExpected = canCreatePartialTrackerFor(virtualFile)
        val isPartialTracker = tracker is PartialLocalLineStatusTracker

        if (isPartialTrackerExpected == isPartialTracker) {
          refreshTracker(tracker)
        }
        else {
          releaseTracker(document)
          installTracker(virtualFile, document)
        }
      }
    }
  }

  private fun registerTrackerInCLM(tracker: LineStatusTracker<*>) {
    if (tracker is PartialLocalLineStatusTracker) {
      val filePath = VcsUtil.getFilePath(tracker.virtualFile)
      changeListManager.registerChangeTracker(filePath, tracker)
    }
  }

  private fun unregisterTrackerInCLM(tracker: LineStatusTracker<*>) {
    if (tracker is PartialLocalLineStatusTracker) {
      val filePath = VcsUtil.getFilePath(tracker.virtualFile)
      changeListManager.unregisterChangeTracker(filePath, tracker)
    }
  }


  private fun canGetBaseRevisionFor(virtualFile: VirtualFile?): Boolean {
    if (isDisposed) return false
    if (virtualFile == null || virtualFile is LightVirtualFile || !virtualFile.isValid) return false
    if (virtualFile.fileType.isBinary || FileUtilRt.isTooLarge(virtualFile.length)) return false
    if (!statusProvider.isSupported(virtualFile)) return false

    val status = FileStatusManager.getInstance(project).getStatus(virtualFile)
    if (status == FileStatus.ADDED ||
        status == FileStatus.DELETED ||
        status == FileStatus.UNKNOWN ||
        status == FileStatus.IGNORED) {
      return false
    }
    return true
  }

  private fun canCreatePartialTrackerFor(virtualFile: VirtualFile): Boolean {
    if (!arePartialChangelistsEnabled(virtualFile)) return false

    val status = FileStatusManager.getInstance(project).getStatus(virtualFile)
    if (status != FileStatus.MODIFIED &&
        status != FileStatus.NOT_CHANGED) return false

    val change = ChangeListManager.getInstance(project).getChange(virtualFile)
    return change != null && change.javaClass == Change::class.java &&
           change.type == Change.Type.MODIFICATION && change.afterRevision is CurrentContentRevision

  }

  private fun arePartialChangelistsEnabled(virtualFile: VirtualFile): Boolean {
    if (!partialChangeListsEnabled) return false
    if (getTrackingMode() == LineStatusTracker.Mode.SILENT) return false

    val vcs = VcsUtil.getVcsFor(project, virtualFile)
    return vcs != null && vcs.arePartialChangelistsSupported()
  }


  @CalledInAwt
  private fun installTracker(virtualFile: VirtualFile,
                             document: Document) {
    if (!canGetBaseRevisionFor(virtualFile)) return

    val changelistId = changeListManager.getChangeList(virtualFile)?.id
    installTracker(virtualFile, document, changelistId, emptyList())
  }

  @CalledInAwt
  private fun installTracker(virtualFile: VirtualFile,
                             document: Document,
                             oldChangesChangelistId: String?,
                             events: List<DocumentEvent>) {
    synchronized(LOCK) {
      if (isDisposed) return
      if (trackers[document] != null) return

      val tracker = if (canCreatePartialTrackerFor(virtualFile)) {
        PartialLocalLineStatusTracker.createTracker(project, document, virtualFile, getTrackingMode(), events)
      }
      else {
        SimpleLocalLineStatusTracker.createTracker(project, document, virtualFile, getTrackingMode())
      }

      trackers.put(document, TrackerData(tracker))

      registerTrackerInCLM(tracker)
      refreshTracker(tracker, oldChangesChangelistId)

      log("Tracker installed", virtualFile)
    }
  }

  @CalledInAwt
  private fun releaseTracker(document: Document) {
    synchronized(LOCK) {
      if (isDisposed) return
      val data = trackers.remove(document) ?: return

      unregisterTrackerInCLM(data.tracker)
      data.tracker.release()

      log("Tracker released", data.tracker.virtualFile)
    }
  }

  private fun getTrackingMode(): LineStatusTracker.Mode {
    val settings = VcsApplicationSettings.getInstance()
    if (!settings.SHOW_LST_GUTTER_MARKERS) return LineStatusTracker.Mode.SILENT
    if (settings.SHOW_WHITESPACES_IN_LST) return LineStatusTracker.Mode.SMART
    return LineStatusTracker.Mode.DEFAULT
  }

  private fun refreshTracker(tracker: LineStatusTracker<*>, changelistId: String? = null) {
    synchronized(LOCK) {
      if (isDisposed) return
      queue.add(tracker.document, BaseRevisionLoader(tracker.document, tracker.virtualFile, changelistId))

      log("Refresh queued", tracker.virtualFile)
    }
  }

  private inner class BaseRevisionLoader(private val document: Document,
                                         private val virtualFile: VirtualFile,
                                         private val changelistId: String?) : Runnable {

    override fun run() {
      val result = try {
        loadBaseRevision()
      }
      catch (e: Exception) {
        LOG.error(e)
        RefreshResult.Error
      }

      handleNewBaseRevision(result)
    }

    private fun loadBaseRevision(): RefreshResult {
      if (isDisposed) return RefreshResult.Canceled
      log("Loading started", virtualFile)

      if (!virtualFile.isValid) {
        log("Loading error: virtual file is not valid", virtualFile)
        return RefreshResult.Error
      }

      if (!canGetBaseRevisionFor(virtualFile)) {
        log("Loading error: cant get base revision", virtualFile)
        return RefreshResult.Error
      }

      val baseContent = statusProvider.getBaseRevision(virtualFile)
      if (baseContent == null) {
        log("Loading error: base revision not found", virtualFile)
        return RefreshResult.Error
      }

      // loads are sequential (in single threaded QueueProcessor);
      // so myLoadCounter can't take less value for greater base revision -> the only thing we want from it
      val newContentInfo = ContentInfo(baseContent.revisionNumber, virtualFile.charset, ourLoadCounter)
      ourLoadCounter++

      synchronized(LOCK) {
        val data = trackers[document]
        if (data == null) {
          log("Loading cancelled: tracker not found", virtualFile)
          return RefreshResult.Canceled
        }

        if (!shouldBeUpdated(data.contentInfo, newContentInfo)) {
          log("Loading cancelled: no need to update", virtualFile)
          return RefreshResult.Canceled
        }
      }

      val lastUpToDateContent = baseContent.loadContent()
      if (lastUpToDateContent == null) {
        log("Loading error: provider failure", virtualFile)
        return RefreshResult.Error
      }

      val converted = StringUtil.convertLineSeparators(lastUpToDateContent)
      log("Loading successful", virtualFile)

      return RefreshResult.Success(converted, newContentInfo)
    }

    private fun handleNewBaseRevision(result: RefreshResult) {
      when (result) {
        is RefreshResult.Canceled -> {
        }
        is RefreshResult.Error -> {
          edt {
            synchronized(LOCK) {
              val data = trackers[document] ?: return@edt

              data.tracker.dropBaseRevision()
              data.contentInfo = null

              checkIfTrackerCanBeReleased(document)
            }
          }
        }
        is RefreshResult.Success -> {
          edt {
            synchronized(LOCK) {
              val data = trackers[document]
              if (data == null) {
                log("Loading finished: tracker already released", virtualFile)
                return@edt
              }
              if (!shouldBeUpdated(data.contentInfo, result.info)) {
                log("Loading finished: no need to update", virtualFile)
                return@edt
              }

              data.contentInfo = result.info
              if (data.tracker is PartialLocalLineStatusTracker) {
                data.tracker.setBaseRevision(result.text, changelistId ?: changeListManager.getChangeList(virtualFile)?.id)
              }
              else {
                data.tracker.setBaseRevision(result.text)
              }
              log("Loading finished: success", virtualFile)
            }
          }
        }
      }
    }
  }

  private inner class MyFileStatusListener : FileStatusListener {
    override fun fileStatusesChanged() {
      onEverythingChanged()
    }

    override fun fileStatusChanged(virtualFile: VirtualFile) {
      onFileChanged(virtualFile)
    }
  }

  private inner class MyEditorFactoryListener : EditorFactoryAdapter() {
    override fun editorCreated(event: EditorFactoryEvent) {
      val editor = event.editor
      if (isTrackedEditor(editor)) {
        requestTrackerFor(editor.document, editor)
      }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
      val editor = event.editor
      if (isTrackedEditor(editor)) {
        releaseTrackerFor(editor.document, editor)
      }
    }

    private fun isTrackedEditor(editor: Editor): Boolean {
      return editor.project == null || editor.project == project
    }
  }

  private inner class MyVirtualFileListener : VirtualFileListener {
    override fun propertyChanged(event: VirtualFilePropertyEvent) {
      if (VirtualFile.PROP_ENCODING == event.propertyName) {
        onFileChanged(event.file)
      }
    }
  }

  private inner class MyDocumentListener : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
      val document = event.document
      if (documentsInDefaultChangeList.contains(document)) return

      val virtualFile = fileDocumentManager.getFile(document) ?: return
      if (getLineStatusTracker(document) != null) return
      if (!canCreatePartialTrackerFor(virtualFile)) return

      val changeList = changeListManager.getChangeList(virtualFile)
      if (changeList != null && !changeList.isDefault) {
        installTracker(virtualFile, document, changeList.id, listOf(event))
        return
      }

      documentsInDefaultChangeList.add(document)
    }
  }

  private inner class MyApplicationListener : ApplicationAdapter() {
    override fun afterWriteActionFinished(action: Any) {
      documentsInDefaultChangeList.clear()

      synchronized(LOCK) {
        val documents = trackers.values.map { it.tracker.document }
        for (document in documents) {
          checkIfTrackerCanBeReleased(document)
        }
      }
    }
  }

  private inner class MyLineStatusTrackerSettingListener : LineStatusTrackerSettingListener {
    override fun settingsUpdated() {
      synchronized(LOCK) {
        val mode = getTrackingMode()
        for (data in trackers.values) {
          val tracker = data.tracker
          val document = tracker.document
          val virtualFile = tracker.virtualFile

          if (tracker.mode == mode) continue

          val isPartialTrackerExpected = canCreatePartialTrackerFor(virtualFile)
          val isPartialTracker = tracker is PartialLocalLineStatusTracker

          if (isPartialTrackerExpected == isPartialTracker) {
            tracker.mode = mode
          }
          else {
            releaseTracker(document)
            installTracker(virtualFile, document)
          }
        }
      }
    }
  }


  private fun shouldBeUpdated(oldInfo: ContentInfo?, newInfo: ContentInfo): Boolean {
    if (oldInfo == null) return true
    if (oldInfo.revision == newInfo.revision && oldInfo.revision != VcsRevisionNumber.NULL) {
      return oldInfo.charset != newInfo.charset
    }
    return oldInfo.loadCounter < newInfo.loadCounter
  }

  private class TrackerData(val tracker: LineStatusTracker<*>,
                            var contentInfo: ContentInfo? = null)

  private class ContentInfo(val revision: VcsRevisionNumber, val charset: Charset, val loadCounter: Long)


  private sealed class RefreshResult {
    class Success(val text: String, val info: ContentInfo) : RefreshResult()
    object Canceled : RefreshResult()
    object Error : RefreshResult()
  }


  private fun edt(task: () -> Unit) {
    GuiUtils.invokeLaterIfNeeded(task, ModalityState.any())
  }

  private fun log(message: String, file: VirtualFile?) {
    if (LOG.isDebugEnabled) {
      if (file != null) {
        LOG.debug(message + "; file: " + file.path)
      }
      else {
        LOG.debug(message)
      }
    }
  }

  private fun warn(message: String, document: Document?) {
    val file = document?.let { fileDocumentManager.getFile(it) }
    warn(message, file)
  }

  private fun warn(message: String, file: VirtualFile?) {
    if (file != null) {
      LOG.warn(message + "; file: " + file.path)
    }
    else {
      LOG.warn(message)
    }
  }
}
