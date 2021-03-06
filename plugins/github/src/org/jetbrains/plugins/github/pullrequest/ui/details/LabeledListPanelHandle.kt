// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.pullrequest.ui.details

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.newui.HorizontalLayout
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.Panels.simplePanel
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.CalledInAwt
import org.jetbrains.annotations.CalledInBackground
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequest
import org.jetbrains.plugins.github.i18n.GithubBundle
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import org.jetbrains.plugins.github.pullrequest.data.service.GHPRSecurityService
import org.jetbrains.plugins.github.ui.InlineIconButton
import org.jetbrains.plugins.github.ui.WrapLayout
import org.jetbrains.plugins.github.ui.util.SingleValueModel
import org.jetbrains.plugins.github.util.CollectionDelta
import org.jetbrains.plugins.github.util.EDT_EXECUTOR
import org.jetbrains.plugins.github.util.GithubUtil.Delegates.equalVetoingObservable
import org.jetbrains.plugins.github.util.handleOnEdt
import org.jetbrains.plugins.github.util.submitIOTask
import java.awt.FlowLayout
import java.awt.event.ActionListener
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.properties.Delegates

internal abstract class LabeledListPanelHandle<T>(private val model: SingleValueModel<GHPullRequest?>,
                                                  private val securityService: GHPRSecurityService,
                                                  emptyText: String, notEmptyText: String) {

  private var isBusy by Delegates.observable(false) { _, _, _ ->
    updateControls()
  }
  private var adjustmentError by Delegates.observable<Throwable?>(null) { _, _, _ ->
    updateControls()
  }

  val label = JLabel().apply {
    foreground = UIUtil.getContextHelpForeground()
    border = JBUI.Borders.empty(6, 0, 6, 5)
  }
  val panel = NonOpaquePanel(WrapLayout(FlowLayout.LEADING, 0, 0))

  private val editButton = InlineIconButton(AllIcons.General.Inline_edit,
                                            AllIcons.General.Inline_edit_hovered).apply {
    border = JBUI.Borders.empty(6, 0)
    actionListener = ActionListener { editList() }
  }
  private val progressLabel = JLabel(AnimatedIcon.Default()).apply {
    border = JBUI.Borders.empty(6, 0)
  }
  private val errorIcon = JLabel(AllIcons.General.Error).apply {
    border = JBUI.Borders.empty(6, 0)
  }

  private val controlsPanel = JPanel(HorizontalLayout(4)).apply {
    isOpaque = false

    add(editButton)
    add(progressLabel)
    add(errorIcon)
  }

  private var list: List<T>? by equalVetoingObservable<List<T>?>(null) { newList ->
    label.text = newList?.let { if (it.isEmpty()) emptyText else notEmptyText }
    label.isVisible = newList != null

    panel.removeAll()
    panel.isVisible = newList != null
    if (newList != null) {
      if (newList.isEmpty()) {
        panel.add(controlsPanel)
      }
      else {
        for (item in newList.dropLast(1)) {
          panel.add(getListItemComponent(item))
        }
        panel.add(getListItemComponent(newList.last(), true))
      }
    }
  }

  init {
    model.addAndInvokeValueChangedListener(::updateList)
    updateControls()
  }

  private fun updateList() {
    list = model.value?.let(::extractItems)
  }

  private fun updateControls() {
    editButton.isVisible = !isBusy && securityService.currentUserCanEditPullRequestsMetadata()
    progressLabel.isVisible = isBusy
    errorIcon.isVisible = adjustmentError != null
    //language=html
    errorIcon.toolTipText = "<html><body>${GithubBundle.message(
      "pull.request.adjustment.failed")}<br/>${adjustmentError?.message.orEmpty()}</body></html> "
  }

  private fun getListItemComponent(item: T, last: Boolean = false) =
    if (!last) getItemComponent(item)
    else simplePanel(getItemComponent(item)).addToRight(controlsPanel).apply {
      isOpaque = false
    }

  abstract fun extractItems(details: GHPullRequest): List<T>?

  abstract fun getItemComponent(item: T): JComponent

  private fun editList() {
    val details = model.value ?: return
    showEditPopup(details, editButton)
      ?.thenComposeAsync(Function<CollectionDelta<T>, CompletableFuture<Unit>> { delta ->
        if (delta == null || delta.isEmpty) {
          CompletableFuture.completedFuture(Unit)
        }
        else {
          adjustmentError = null
          isBusy = true
          ProgressManager.getInstance().submitIOTask(EmptyProgressIndicator()) {
            adjust(it, details, delta)
          }
        }
      }, EDT_EXECUTOR)
      ?.handleOnEdt { _, error ->
        adjustmentError = error
        isBusy = false
      }
  }

  @CalledInAwt
  abstract fun showEditPopup(details: GHPullRequest, parentComponent: JComponent): CompletableFuture<CollectionDelta<T>>?

  @CalledInBackground
  abstract fun adjust(indicator: ProgressIndicator, pullRequestId: GHPRIdentifier, delta: CollectionDelta<T>)
}