// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.refactoring.extractMethod.newImpl.inplace

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.ide.IdeEventQueue
import com.intellij.ide.util.PropertiesComponent
import com.intellij.internal.statistic.collectors.fus.ui.GotItUsageCollector
import com.intellij.internal.statistic.collectors.fus.ui.GotItUsageCollectorGroup
import com.intellij.java.refactoring.JavaRefactoringBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.injected.changesHandler.range
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.extractMethod.ExtractMethodDialog
import com.intellij.refactoring.extractMethod.ExtractMethodHandler
import com.intellij.refactoring.extractMethod.newImpl.*
import com.intellij.refactoring.extractMethod.newImpl.structures.ExtractOptions
import com.intellij.refactoring.rename.inplace.InplaceRefactoring
import com.intellij.refactoring.rename.inplace.TemplateInlayUtil
import com.intellij.refactoring.suggested.SuggestedRefactoringProvider
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.GotItTooltip
import org.jetbrains.annotations.NonNls
import java.awt.Point

class InplaceMethodExtractor(val editor: Editor, val extractOptions: ExtractOptions, private val popupProvider: ExtractMethodPopupProvider)
  : InplaceRefactoring(editor, null, extractOptions.project) {

  companion object {
    private val INPLACE_METHOD_EXTRACTOR = Key<InplaceMethodExtractor>("InplaceMethodExtractor")

    fun getActiveExtractor(editor: Editor): InplaceMethodExtractor? {
      return TemplateManagerImpl.getTemplateState(editor)?.properties?.get(INPLACE_METHOD_EXTRACTOR) as? InplaceMethodExtractor
    }

    private fun setActiveExtractor(editor: Editor, extractor: InplaceMethodExtractor) {
      TemplateManagerImpl.getTemplateState(editor)?.properties?.put(INPLACE_METHOD_EXTRACTOR, extractor)
    }
  }

  init {
    initPopupOptionsAdvertisement()
  }

  private val fragmentsToRevert = mutableListOf<FragmentState>()

  private val caretToRevert: Int = editor.caretModel.currentCaret.offset

  private val selectionToRevert: TextRange? = ExtractMethodHelper.findEditorSelection(editor)

  private val extractedRange = enclosingTextRangeOf(extractOptions.elements.first(), extractOptions.elements.last())

  private lateinit var methodNameRange: RangeMarker

  private lateinit var methodCallExpressionRange: RangeMarker

  private var gotItBalloon: Balloon? = null

  private lateinit var preview: EditorCodePreview

  fun prepareCodeForTemplate() {
    val project = extractOptions.project
    val document = editor.document

    val elements = extractOptions.elements
    val callRange = document.createGreedyRangeMarker(enclosingTextRangeOf(elements.first(), elements.last()))
    val callText = document.getText(callRange.range)
    val replacedCall = FragmentState(callRange, callText)
    fragmentsToRevert.add(replacedCall)

    val startSibling = extractOptions.anchor.nextSibling
    val endSibling = PsiTreeUtil.skipWhitespacesForward(startSibling) ?: startSibling
    val methodRange = document.createGreedyRangeMarker(enclosingTextRangeOf(startSibling, endSibling))
    val methodText = document.getText(methodRange.range)
    val replacedMethod = FragmentState(methodRange, methodText)
    fragmentsToRevert.add(replacedMethod)

    val javaFile = extractOptions.anchor.containingFile as PsiJavaFile
    val importRange = document.createGreedyRangeMarker(javaFile.importList?.textRange ?: TextRange(0, 0))
    val replacedImport = FragmentState(importRange, document.getText(importRange.range))
    fragmentsToRevert.add(replacedImport)

    val (callElements, method) = MethodExtractor().extractMethod(extractOptions.copy(methodName = "extracted"))
    val callExpression = PsiTreeUtil.findChildOfType(callElements.first(), PsiMethodCallExpression::class.java, false)!!
    editor.caretModel.moveToOffset(callExpression.textOffset)
    PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)
    setElementToRename(method)

    methodNameRange = editor.document.createGreedyRangeMarker(method.nameIdentifier!!.textRange)
    methodCallExpressionRange = editor.document.createGreedyRangeMarker(callExpression.methodExpression.textRange)

    preview = EditorCodePreview.create(editor)

    val callLines = findLines(document, enclosingTextRangeOf(callElements.first(), callElements.last()))
    val callNavigatableRange = document.createGreedyRangeMarker(callExpression.methodExpression.textRange)
    val file = method.containingFile.virtualFile
    Disposer.register(preview, Disposable { callNavigatableRange.dispose() })
    preview.addPreview(callLines) { navigate(project, file, callNavigatableRange.endOffset)}

    val methodLines = findLines(document, method.textRange).trimToLength(4)
    preview.addPreview(methodLines) { navigate(project, file, methodNameRange.endOffset) }
  }

  private fun showNavigationGotIt(templateState: TemplateState){
    val selectedRange = templateState.currentVariableRange ?: return
    val offset = minOf(selectedRange.startOffset + 3, selectedRange.endOffset)
    val gotoDeclarationShortcut = KeymapUtil.getFirstKeyboardShortcutText(IdeActions.ACTION_GOTO_DECLARATION)
    val message = JavaRefactoringBundle.message("extract.method.gotit.navigation", gotoDeclarationShortcut)
    GotItTooltip("extract.method.gotit.navigate", message, templateState).showInEditor(templateState.editor, offset) { gotItBalloon = it }
  }

  private fun showChangeSignatureGotIt(){
    gotItBalloon?.hide()
    val offset = minOf(methodNameRange.startOffset + 3, methodNameRange.endOffset)
    val disposable = Disposer.newDisposable()
    EditorUtil.disposeWithEditor(editor, disposable)
    val moveLeftShortcut = KeymapUtil.getFirstKeyboardShortcutText(IdeActions.MOVE_ELEMENT_LEFT)
    val moveRightShortcut = KeymapUtil.getFirstKeyboardShortcutText(IdeActions.MOVE_ELEMENT_RIGHT)
    val contextActionShortcut = KeymapUtil.getFirstKeyboardShortcutText("ShowIntentionActions")
    val message = JavaRefactoringBundle.message("extract.method.gotit.signature", contextActionShortcut, moveLeftShortcut, moveRightShortcut)
    GotItTooltip("extract.method.signature.change", message, disposable).showInEditor(editor, offset)
  }

  private fun GotItTooltip.showInEditor(editor: Editor, offset: Int, balloonCreated: (Balloon) -> Unit = {}) {
    fun getPosition(): Point = editor.offsetToXY(offset)

    fun isVisible(): Boolean {
      val position = getPosition()
      val visibleArea = EditorCodePreview.getActivePreview(editor)?.editorVisibleArea ?: editor.scrollingModel.visibleArea
      return visibleArea.contains(position)
    }

    withMaxWidth(250)
    withPosition(Balloon.Position.above)

    if (isVisible()) {
      setOnBalloonCreated { balloon -> editor.scrollingModel.addVisibleAreaListener({
          if (isVisible()) {
            balloon.revalidate()
          } else {
            balloon.hide(true)
            GotItUsageCollector.instance.logClose(id, GotItUsageCollectorGroup.CloseType.AncestorRemoved)
          }
        }, balloon)

        balloonCreated(balloon)
      }

      show(editor.contentComponent) { getPosition() }
    }
  }

  override fun performInplaceRefactoring(nameSuggestions: LinkedHashSet<String>?): Boolean {
    ApplicationManager.getApplication().runWriteAction { prepareCodeForTemplate() }
    val result = super.performInplaceRefactoring(nameSuggestions)
    ApplicationManager.getApplication().runWriteAction { setMethodName(extractOptions.methodName) }
    return result
  }

  override fun revertState() {
    super.revertState()
    WriteCommandAction.runWriteCommandAction(myProject) {
      fragmentsToRevert.forEach { fragment ->
        editor.document.replaceString(fragment.range.startOffset, fragment.range.endOffset, fragment.text)
      }
      PsiDocumentManager.getInstance(myProject).commitDocument(editor.document)
      editor.caretModel.moveToOffset(caretToRevert)
      if (selectionToRevert != null) {
        editor.selectionModel.setSelection(selectionToRevert.startOffset, selectionToRevert.endOffset)
      }
    }
  }

  override fun afterTemplateStart() {
    super.afterTemplateStart()
    popupProvider.setChangeListener { restartInplace() }
    popupProvider.setShowDialogAction { restartInDialog() }
    popupProvider.setNavigateMethodAction { finishAndGotoDeclaration() }
    val templateState = TemplateManagerImpl.getTemplateState(myEditor) ?: return
    val editor = templateState.editor as? EditorImpl ?: return
    val presentation = TemplateInlayUtil.createSettingsPresentation(editor)
    val offset = templateState.currentVariableRange?.endOffset ?: return
    TemplateInlayUtil.createNavigatableButtonWithPopup(templateState, offset, presentation, popupProvider.panel) ?: return
    fragmentsToRevert.forEach { Disposer.register(templateState, it) }
    setActiveExtractor(editor, this)

    showNavigationGotIt(templateState)

    Disposer.register(templateState, preview)
    Disposer.register(templateState, { SuggestedRefactoringProvider.getInstance(extractOptions.project).reset() })
    Disposer.register(templateState, { methodNameRange.dispose() })
    Disposer.register(templateState, { methodCallExpressionRange.dispose() })

    val connection = myProject.messageBus.connect(templateState)
    connection.subscribe(AnActionListener.TOPIC, object: AnActionListener {
      override fun afterActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        if (action is GotoDeclarationAction){
          showChangeSignatureGotIt()
          templateState.gotoEnd(false)
        }
      }
    })

    installMethodNameValidation(templateState)
  }

  private fun setMethodName(methodName: String) {
    editor.document.replaceString(methodCallExpressionRange.startOffset, methodCallExpressionRange.endOffset, methodName)
    editor.document.replaceString(methodNameRange.startOffset, methodNameRange.endOffset, methodName)
  }

  private fun installMethodNameValidation(templateState: TemplateState) {
    templateState.addTemplateStateListener(object: TemplateEditingAdapter() {

      var restartOptions: ExtractOptions? = null
      var errorMessage: @NonNls String? = null

      override fun beforeTemplateFinished(state: TemplateState, template: Template?) {
        val methodName = editor.document.getText(TextRange(methodNameRange.startOffset, methodNameRange.endOffset))
        fun isValidName(): Boolean = PsiNameHelper.getInstance(myProject).isIdentifier(methodName)
        fun hasSingleResolve(): Boolean {
          val file = PsiDocumentManager.getInstance(myProject).getPsiFile(editor.document) ?: return false
          val methodCall = PsiTreeUtil.findElementOfClassAtOffset(file, methodCallExpressionRange.startOffset, PsiMethodCallExpression::class.java, true)
          return methodCall?.resolveMethod() != null
        }
        errorMessage = when {
          ! isValidName() -> JavaRefactoringBundle.message("extract.method.error.invalid.name")
          ! hasSingleResolve() -> JavaRefactoringBundle.message("extract.method.error.method.conflict")
          else -> null
        }
        if (errorMessage != null) {
          restartOptions = revertAndMapOptions(popupProvider.annotate, popupProvider.makeStatic)
        }
      }

      override fun templateFinished(template: Template, brokenOff: Boolean) {
        if (! brokenOff) restartWithInvalidName()
      }

      override fun templateCancelled(template: Template?) {
        restartWithInvalidName()
      }

      private fun restartWithInvalidName(){
        ApplicationManager.getApplication().invokeLater {
          val message = errorMessage
          val options = restartOptions
          if (message != null && options != null) {
            WriteCommandAction.runWriteCommandAction(myProject) {
              val extractor = InplaceMethodExtractor(editor, options, popupProvider)
              extractor.performInplaceRefactoring(linkedSetOf())
              CommonRefactoringUtil.showErrorHint(myProject, editor, message, ExtractMethodHandler.getRefactoringName(), null)
            }
          }
        }
      }
    })
  }

  private fun finishAndGotoDeclaration() {
    val template = TemplateManagerImpl.getTemplateState(editor)
    if (template != null) {
      IdeEventQueue.getInstance().popupManager.closeAllPopups()
      val file = FileDocumentManager.getInstance().getFile(editor.document)
      if (file != null) {
        navigate(myProject, file, methodNameRange.endOffset)
      }
      showChangeSignatureGotIt()
      template.gotoEnd(false)
    }
  }

  fun restartInDialog() {
    val newOptions = revertAndMapOptions(popupProvider.annotate, popupProvider.makeStatic)
    MethodExtractor().doDialogExtract(newOptions)
  }

  private fun revertAndMapOptions(annotate: Boolean?, makeStatic: Boolean?): ExtractOptions {
    if (annotate != null) {
      PropertiesComponent.getInstance(extractOptions.project).setValue(ExtractMethodDialog.EXTRACT_METHOD_GENERATE_ANNOTATIONS, annotate, true)
    }

    val methodNameRange = TemplateManagerImpl.getTemplateState(editor)?.currentVariableRange ?: throw IllegalStateException()
    val methodName = editor.document.getText(methodNameRange)
    val containingClass = extractOptions.anchor.containingClass ?: throw IllegalStateException()
    performCleanup()

    val elements = ExtractSelector().suggestElementsToExtract(containingClass.containingFile, extractedRange)
    val analyzer = CodeFragmentAnalyzer(elements)
    var options = findExtractOptions(elements).copy(methodName = methodName)
    options = ExtractMethodPipeline.withTargetClass(analyzer, options, containingClass)!!
    options = if (makeStatic == true) ExtractMethodPipeline.withForcedStatic(analyzer, options)!! else options
    return options
  }

  private fun restartInplace() {
    val newOptions = revertAndMapOptions(popupProvider.annotate, popupProvider.makeStatic)
    WriteCommandAction.runWriteCommandAction(myProject) {
      InplaceMethodExtractor(editor, newOptions, popupProvider).performInplaceRefactoring(linkedSetOf())
    }
  }

  override fun performRefactoring(): Boolean {
    return false
  }

  override fun performCleanup() {
    revertState()
  }

  override fun shouldSelectAll(): Boolean = false

  override fun getCommandName(): String = ExtractMethodHandler.getRefactoringName()

  private data class FragmentState(val range: RangeMarker, val text: String) : Disposable {
    override fun dispose() {
      range.dispose()
    }
  }

  private fun Document.createGreedyRangeMarker(range: TextRange): RangeMarker {
    return createRangeMarker(range).also {
      it.isGreedyToLeft = true
      it.isGreedyToRight = true
    }
  }

  private fun enclosingTextRangeOf(start: PsiElement, end: PsiElement): TextRange = start.textRange.union(end.textRange)

  private fun IntRange.trimToLength(maxLength: Int) = first until first + minOf(maxLength, last - first + 1)

  private fun navigate(project: Project, file: VirtualFile, offset: Int) {
    val descriptor = OpenFileDescriptor(project, file, offset)
    descriptor.navigate(true)
    descriptor.dispose()
  }

  private fun findLines(document: Document, range: TextRange): IntRange {
    return document.getLineNumber(range.startOffset)..document.getLineNumber(range.endOffset)
  }
}