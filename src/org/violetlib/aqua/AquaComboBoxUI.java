/*
 * Copyright (c) 2015-2024 Alan Snyder.
 * All rights reserved.
 *
 * You may not use, copy or modify this file, except in compliance with the license agreement. For details see
 * accompanying license terms.
 */

/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.violetlib.aqua;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.ActionMapUIResource;
import javax.swing.plaf.ComboBoxUI;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.text.JTextComponent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.violetlib.aqua.AquaUtils.RecyclableSingleton;
import org.violetlib.aqua.ClientPropertyApplicator.Property;
import org.violetlib.geom.ExpandableOutline;
import org.violetlib.jnr.Insetter;
import org.violetlib.jnr.LayoutInfo;
import org.violetlib.jnr.aqua.*;
import org.violetlib.jnr.aqua.AquaUIPainter.ComboBoxWidget;
import org.violetlib.jnr.aqua.AquaUIPainter.PopupButtonWidget;
import org.violetlib.jnr.aqua.AquaUIPainter.Size;
import org.violetlib.jnr.aqua.AquaUIPainter.State;

import static org.violetlib.jnr.aqua.AquaUIPainter.ComboBoxWidget.*;
import static org.violetlib.jnr.aqua.AquaUIPainter.PopupButtonWidget.*;

// Inspired by MetalComboBoxUI, which also has a combined text-and-arrow button for noneditables
public class AquaComboBoxUI extends BasicComboBoxUI
        implements AquaUtilControlSize.Sizeable, FocusRingOutlineProvider, ToolbarSensitiveUI, AquaComponentUI,
        SystemPropertyChangeManager.SystemPropertyChangeListener {

    public static @NotNull ComponentUI createUI(JComponent c) {
        return new AquaComboBoxUI();
    }

    // A JComboBox maps to two different kinds of views depending upon whether the JComboBox is editable or not. This
    // code is complex because the editable attribute can be changed on the fly. A non-editable JComboBox maps to a
    // popup button, which shares many characteristics with ordinary buttons.

    public static final String POPDOWN_CLIENT_PROPERTY_KEY = "JComboBox.isPopDown"; // legacy from Aqua LAF
    public static final String ISSQUARE_CLIENT_PROPERTY_KEY = "JComboBox.isSquare"; // legacy from Aqua LAF
    public static final String STYLE_CLIENT_PROPERTY_KEY = "JComboBox.style";
    public static final String TITLE_CLIENT_PROPERTY_KEY = "JComboBox.title";

    private static final AquaUIPainter painter = AquaPainting.create();

    private int oldMaximumRowCount;
    protected Dimension cachedPreferredSize = new Dimension( 0, 0 );
    protected AquaComboBoxButton arrowButton;
    protected HierarchyListener hierarchyListener;
    protected @NotNull BasicContextualColors colors;
    protected @Nullable AppearanceContext appearanceContext;
    protected @NotNull JList<Object> buttonList;
    private final PropertyChangeListener propertyChangeListener = new AquaPropertyChangeListener();
    private final DocumentListener documentListener = new MyDocumentListener();
    private final HierarchyListener popupListener = new MyPopupListener();
    private @Nullable AquaComboBoxRenderer buttonRenderer;
    private @Nullable AquaComboBoxRenderer listRenderer;

    // derived configuration attributes
    protected Size sizeVariant;
    protected boolean isPopDown;
    protected String style;
    protected @Nullable AquaCellEditorPolicy.CellStatus cellStatus;
    protected boolean isDefaultStyle;
    protected boolean isTextured;

    // cached attributes
    protected boolean isToolbar;
    protected AbstractComboBoxLayoutConfiguration layoutConfiguration;

    public AquaComboBoxUI() {
        colors = AquaColors.CLEAR_CONTROL_COLORS;
        buttonList = new JList<>();
    }

    public void installUI(@NotNull JComponent c) {
        super.installUI(c);

        sizeVariant = AquaUtilControlSize.getUserSizeFrom(comboBox);
        if (buttonRenderer == null) {
            buttonRenderer = new AquaComboBoxRenderer(comboBox, false, sizeVariant);
        }
        if (listRenderer == null) {
            listRenderer = new AquaComboBoxRenderer(comboBox, true, sizeVariant);
        }
        buttonList.setCellRenderer(buttonRenderer);

        LookAndFeel.installProperty(c, "opaque", false);
        oldMaximumRowCount = comboBox.getMaximumRowCount();
        int maximumRows = UIManager.getInt("ComboBox.maximumRowCount");
        if (maximumRows > 0) {
            comboBox.setMaximumRowCount(maximumRows);
        }
        comboBox.setRequestFocusEnabled(false);
        //comboBox.putClientProperty(DEFAULT_FONT_PROPERTY, comboBox.getFont());
        isToolbar = AquaUtils.isOnToolbar(comboBox);
        updateFromRenderer();
        configure(sizeVariant);
        configureFocusable(comboBox);
    }

    public void uninstallUI(@NotNull JComponent c) {
        comboBox.setMaximumRowCount(oldMaximumRowCount);
        super.uninstallUI(c);
    }

    protected void installListeners() {
        super.installListeners();
        AquaUtilControlSize.addSizePropertyListener(comboBox);

        // An editable combo box is normally focusable.
        // A non-editable combo box (a pull down or pop menu button) is focusable only if Full Keyboard Access
        // is enabled.
        // Because editability is a dynamic attribute, we respond to changes unconditionally, but make the
        // effect conditional on the editability of the combo box.

        OSXSystemProperties.register(comboBox);
        hierarchyListener = new MyHierarchyListener();
        comboBox.addHierarchyListener(hierarchyListener);
        comboBox.addPropertyChangeListener(propertyChangeListener);
        ComboPopup popup = getPopup();
        if (popup instanceof AquaComboBoxPopup) {
            ((AquaComboBoxPopup) popup).addHierarchyListener(popupListener);
        }
        AppearanceManager.installListeners(comboBox);
        AquaUtils.installToolbarSensitivity(comboBox);
    }

    protected void uninstallListeners() {
        AquaUtils.uninstallToolbarSensitivity(comboBox);
        AppearanceManager.uninstallListeners(comboBox);
        ComboPopup popup = getPopup();
        if (popup instanceof AquaComboBoxPopup) {
            ((AquaComboBoxPopup) popup).removeHierarchyListener(popupListener);
        }
        comboBox.removePropertyChangeListener(propertyChangeListener);
        hierarchyListener = null;
        AquaUtilControlSize.removeSizePropertyListener(comboBox);
        OSXSystemProperties.unregister(comboBox);
        super.uninstallListeners();
    }

    protected void installComponents() {
        super.installComponents();
        // client properties must be applied after the components have been installed,
        // because isSquare and isPopdown are applied to the installed button
        getApplicator().attachAndApplyClientProperties(comboBox);
    }

    protected void uninstallComponents() {
        getApplicator().removeFrom(comboBox);
        super.uninstallComponents();
    }

    private class MyHierarchyListener implements HierarchyListener {
        @Override
        public void hierarchyChanged(HierarchyEvent e) {
            AquaComboBoxUI ui = AquaUtils.getUI(comboBox, AquaComboBoxUI.class);
            if (ui != null) {
                ui.respondToHierarchyChange();
            }
        }
    }

    @Override
    public void systemPropertyChanged(JComponent c, Object type) {
        if (type.equals(OSXSystemProperties.USER_PREFERENCE_CHANGE_TYPE)) {
            configureFocusable((JComboBox) c);
        }
    }

    private void configureFocusable(JComboBox c) {
        boolean isFocusable = OSXSystemProperties.isFullKeyboardAccessEnabled();
        if (!c.isEditable()) {
            c.setFocusable(isFocusable);
        }
    }

//    protected @NotNull ItemListener createItemListener() {
//        return new ItemListener() {
//            long lastBlink = 0L;
//            public void itemStateChanged(ItemEvent e) {
//                if (e.getStateChange() != ItemEvent.SELECTED) {
//                    return;
//                }
//                if (!popup.isVisible()) {
//                    return;
//                }
//
//                // sometimes, multiple selection changes can occur while the popup is up,
//                // and blinking more than "once" (in a second) is not desirable
//                long now = System.currentTimeMillis();
//                if (now - 1000 < lastBlink) return;
//                lastBlink = now;
//
//                JList<Object> itemList = popup.getList();
//                ListUI listUI = itemList.getUI();
//                if (!(listUI instanceof AquaListUI)) {
//                    return;
//                }
//                AquaListUI aquaListUI = (AquaListUI)listUI;
//
//                int selectedIndex = comboBox.getSelectedIndex();
//                ListModel<Object> dataModel = itemList.getModel();
//                if (dataModel == null) {
//                    return;
//                }
//
//                Object value = dataModel.getElementAt(selectedIndex);
//                AquaUtils.blinkMenu(selected -> aquaListUI.repaintCell(value, selectedIndex, selected));
//            }
//        };
//    }

    class AquaPropertyChangeListener implements PropertyChangeListener {
        public void propertyChange(PropertyChangeEvent e) {
            String name = e.getPropertyName();
            if (name.equals("enabled")) {
                configureAppearanceContext(null);
            } else if (name.equals("renderer")) {
                updateFromRenderer();
            }
        }
    }

    @Override
    public void appearanceChanged(@NotNull JComponent c, @NotNull AquaAppearance appearance) {
        configureAppearanceContext(appearance);
    }

    @Override
    public void activeStateChanged(@NotNull JComponent c, boolean isActive) {
        configureAppearanceContext(null);
    }

    protected void configureAppearanceContext(@Nullable AquaAppearance appearance) {
        if (appearance == null) {
            appearance = AppearanceManager.ensureAppearance(comboBox);
        }
        AquaUIPainter.State state = getState();
        appearanceContext = new AppearanceContext(appearance, state, false, false);
        // If the combo box is being used as a cell renderer component, it is up to the cell renderer to configure
        // its colors.
        if (cellStatus == null) {
            AquaColors.installColors(comboBox, appearanceContext, colors);
        }
        comboBox.repaint();
    }

    protected void updateFromRenderer() {
        ListCellRenderer customRenderer = getCustomRenderer();
        if (buttonRenderer != null) {
            buttonRenderer.setCustomRenderer(customRenderer);
        }
        if (listRenderer != null) {
            listRenderer.setCustomRenderer(customRenderer);
        }
    }

    protected @Nullable ListCellRenderer<?> getCustomRenderer()
    {
        ListCellRenderer<?> r = comboBox.getRenderer();
        if (r == null || r instanceof UIResource) {
            return null;
        }
        return r;
    }

    protected @NotNull AquaUIPainter.State getState() {
        boolean isActive = AquaFocusHandler.isActive(comboBox);
        if (!comboBox.isEnabled()) {
            return isActive ? State.DISABLED : State.DISABLED_INACTIVE;
        }

        if (isActive && arrowButton != null) {
            if (isPopupReallyVisible(popup)) {
                return State.PRESSED;
            }

            ButtonModel model = arrowButton.getModel();
            if (model.isArmed() && model.isPressed()) {
                return State.PRESSED;
            }

            if (arrowButton.isRollover) {
                return State.ROLLOVER;
            }
        }

        // Most combo boxes do not display differently when inactive.
        // The exceptions:
        // Default style buttons lose their colored arrow button.
        // Textured buttons display dimmed.

        if (!isActive) {
            Object w = getWidget();
            if (w == BUTTON_POP_DOWN || w == BUTTON_POP_UP || w == BUTTON_COMBO_BOX || isTextured) {
                return State.INACTIVE;
            }
        }

        return State.ACTIVE;
    }

    private static boolean isPopupReallyVisible(@NotNull ComboPopup p)
    {
        // Workaround: popup.isVisible() can return true when isShowing() returns false, leading to a failure to
        // restore the button state after the popup is dismissed.
        if (!p.isVisible()) {
            return false;
        }
        if (p instanceof Component) {
            Component c = (Component) p;
            return c.isShowing();
        }
        return true;
    }

    @Override
    public void addEditor() {
        super.addEditor();
        isMinimumSizeDirty = true;  // workaround for bug in BasicComboBoxUI, editable change does not set this flag
    }

    @Override
    public void removeEditor() {
        super.removeEditor();
        isMinimumSizeDirty = true;  // workaround for bug in BasicComboBoxUI, editable change does not set this flag
    }

    @Override
    public void update(@NotNull Graphics g, @NotNull JComponent c) {
        AppearanceManager.registerCurrentAppearance(c);
        super.update(g, c);
    }

    @Override
    public void paint(@NotNull Graphics g, @NotNull JComponent c) {

        int width = comboBox.getWidth();
        int height = comboBox.getHeight();

        if (height <= 0 || width <= 0) {
            return;
        }

        // paint the button

        Configuration bg = getConfiguration();
        AquaUtils.configure(painter, comboBox, width, height);
        if (bg != null) {
            painter.getPainter(bg).paint(g, 0, 0);
        }

        if (!comboBox.isEditable()) {
            paintButtonValue(g);
        }
    }

    public @Nullable Configuration getConfiguration() {

        State state = getState();

        LayoutConfiguration g = getLayoutConfiguration();
        if (g instanceof PopupButtonLayoutConfiguration) {
            PopupButtonLayoutConfiguration bg = (PopupButtonLayoutConfiguration) g;
            return new PopupButtonConfiguration(bg, state);

        } else if (g instanceof ComboBoxLayoutConfiguration) {
            ComboBoxLayoutConfiguration bg = (ComboBoxLayoutConfiguration) g;
            boolean isFocused = false; // comboBox.hasFocus() || comboBox.getEditor().getEditorComponent().hasFocus();
            return new ComboBoxConfiguration(bg, state, isFocused);
        } else {
            return null;
        }
    }

    private boolean determineIsDefaultStyle() {
        Object w = getWidget();
        return w == BUTTON_COMBO_BOX || w == BUTTON_POP_DOWN || w == BUTTON_POP_UP;
    }

    private boolean determineIsTextured() {
        Object w = getWidget();
        return w == BUTTON_POP_DOWN_TEXTURED
                || w == BUTTON_POP_UP_TEXTURED
                || w == BUTTON_COMBO_BOX_TEXTURED
                || w == BUTTON_POP_DOWN_TEXTURED_TOOLBAR
                || w == BUTTON_POP_UP_TEXTURED_TOOLBAR
                || w == BUTTON_COMBO_BOX_TEXTURED_TOOLBAR;
    }

    private @Nullable AquaCellEditorPolicy.CellStatus determineCellStatus() {
        return AquaCellEditorPolicy.getInstance().getCellStatus(comboBox);
    }

    /**
     * Modify the title icon if necessary based on the combo box (button) style and state.
     * @param icon The supplied icon.
     * @return the icon to use.
     */
    public @NotNull Icon getIcon(@NotNull Icon icon) {
        State st = getState();

        if (icon instanceof ImageIcon) {
            ImageIcon ii = (ImageIcon) icon;
            if (AquaImageFactory.isTemplateImage(ii.getImage())) {
                Color color = comboBox.getForeground();
                if (color != null) {
                    Image im = ii.getImage();
                    im = AquaImageFactory.getProcessedImage(im, color);
                    return new ImageIconUIResource(im);
                }
            }
        }

        if (st == State.PRESSED) {
            return AquaIcon.createPressedDarkIcon(icon);
        }

        if (shouldUseDisabledIcon()) {
            AquaAppearance appearance = AppearanceManager.getAppearance(comboBox);
            return appearance.isDark()
                    ? AquaIcon.createPressedDarkIcon(icon)
                    : AquaIcon.createDisabledLightIcon(icon);
        }

        return icon;
    }

    protected boolean shouldUseDisabledIcon()
    {
        State st = getState();
        if (st == State.DISABLED || st == State.DISABLED_INACTIVE) {
            return true;

        } else if (st == State.INACTIVE) {
            if (isTextured) {
                return OSXSystemProperties.OSVersion < 1015;
            }
        }
        return false;
    }

    @Override
    public @Nullable Shape getFocusRingOutline(@NotNull JComponent c) {
        LayoutConfiguration g = getLayoutConfiguration();
        if (g != null) {
            AquaUtils.configure(painter, comboBox, c.getWidth(), c.getHeight());
            return painter.getOutline(g);
        } else {
            return null;
        }
    }

    private void paintButtonValue(@NotNull Graphics g) {
        ListCellRenderer<Object> renderer = comboBox.getRenderer();

        Object displayedItem = null;

        AquaComboBoxType type = getComboBoxType(comboBox);
        if (type == AquaComboBoxType.PULL_DOWN_MENU_BUTTON) {
            Object value = comboBox.getClientProperty(TITLE_CLIENT_PROPERTY_KEY);
            if (value != null) {
                if (value instanceof Icon) {
                    value = getIcon((Icon) value);
                }
                displayedItem = value;
            }
        } else {
            displayedItem = comboBox.getSelectedItem();
        }

        int top = 0;
        int left = 0;
        int width = comboBox.getWidth();
        int height = comboBox.getHeight();

        LayoutConfiguration bg = getLayoutConfiguration();
        if (bg != null) {
            Rectangle bounds = getContentBounds(bg);
            if (bounds != null) {
                left = bounds.x;
                top = bounds.y;
                width = bounds.width;
                height = bounds.height;
            }
        }

        if (padding != null) {
            left += padding.left;
            top += padding.top;
            width -= padding.left;  // do not use right padding here, if we need the room we should use it
            height -= padding.top;  // do not use bottom padding here, if we need the room we should use it
        }

        // fake it out! not renderPressed
        buttonList.setBackground(AquaColors.CLEAR);
        buttonList.setForeground(comboBox.getForeground());
        Component c = renderer.getListCellRendererComponent(buttonList, displayedItem, -1, false, false);
        // AquaUtils.logDebug("Renderer: " + renderer);
        c.setFont(currentValuePane.getFont());
        updateRendererStyle(c);

        // Sun Fix for 4238829: should lay out the JPanel.
        boolean shouldValidate = false;
        if (c instanceof JPanel) {
            shouldValidate = true;
        }

        currentValuePane.paintComponent(g, c, comboBox, left, top, width, height, shouldValidate);
    }

    protected @Nullable Rectangle getContentBounds(@NotNull LayoutConfiguration g) {
        AquaUtils.configure(painter, comboBox, comboBox.getWidth(), comboBox.getHeight());
        if (g instanceof ComboBoxLayoutConfiguration) {
            return AquaUtils.toMinimumRectangle(painter.getComboBoxEditorBounds((ComboBoxLayoutConfiguration) g));
        } else if (g instanceof PopupButtonLayoutConfiguration) {
            return AquaUtils.toMinimumRectangle(painter.getPopupButtonContentBounds((PopupButtonLayoutConfiguration) g));
        } else {
            return null;
        }
    }

    protected @Nullable Insetter getContentInsets(@NotNull LayoutConfiguration g) {
        if (g instanceof ComboBoxLayoutConfiguration) {
            return painter.getLayoutInfo().getComboBoxEditorInsets((ComboBoxLayoutConfiguration) g);
        } else if (g instanceof PopupButtonLayoutConfiguration) {
            return painter.getLayoutInfo().getPopupButtonContentInsets((PopupButtonLayoutConfiguration) g);
        } else {
            return null;
        }
    }

    @Override
    protected @NotNull ListCellRenderer<Object> createRenderer() {
        if (buttonRenderer == null) {
            buttonRenderer = new AquaComboBoxRenderer(comboBox, false, AquaUtilControlSize.getUserSizeFrom(comboBox));
        }
        return buttonRenderer;
    }

    @Override
    protected @NotNull ComboPopup createPopup() {
        return new AquaComboBoxPopup(comboBox);
    }

    @Override
    protected @NotNull JButton createArrowButton() {
        return arrowButton = new AquaComboBoxButton(this, comboBox);
    }

    @Override
    protected @NotNull ComboBoxEditor createEditor() {
        return new AquaComboBoxEditor();
    }

    public @NotNull ListCellRenderer<Object> getListCellRenderer() {
        if (listRenderer == null) {
            listRenderer = new AquaComboBoxRenderer(comboBox, true, AquaUtilControlSize.getUserSizeFrom(comboBox));
        }
        return listRenderer;
    }

    private class MyPopupListener implements HierarchyListener {
        @Override
        public void hierarchyChanged(@NotNull HierarchyEvent e) {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                popupVisibilityChanged();
            }
        }
    }

    protected void popupVisibilityChanged() {
        // Workaround: when we are notified that the popup is no longer showing, the button remains in the rollover
        // state indefinitely because it never receives a mouse exited event.
        if (!isPopupReallyVisible(popup) && arrowButton != null) {
            arrowButton.isRollover = false;
        }
        configureAppearanceContext(null); // the arrow button may need to be repainted with a new state
    }

    @Override
    protected void configureEditor() {
        super.configureEditor();

        // We now know the combo box is editable.

        comboBox.setFocusable(true);

        if (editor instanceof JTextField) {
            JTextField tf = (JTextField) editor;

            if (!(editor instanceof AquaCustomComboTextField)) {
                tf.setUI(new AquaComboBoxEditorUI());
                tf.setBorder(null);
            }
        }

        if (editor instanceof JTextComponent) {
            JTextComponent tc = (JTextComponent) editor;
            tc.getDocument().addDocumentListener(documentListener);
        }
    }

    @Override
    protected void unconfigureEditor() {
        super.unconfigureEditor();

        // Either we now know that the combo box is not editable, or the UI is being uninstalled.

        if (!comboBox.isEditable()) {
            comboBox.setFocusable(OSXSystemProperties.isFullKeyboardAccessEnabled());
        }

        if (editor instanceof JTextComponent) {
            JTextComponent tc = (JTextComponent) editor;
            tc.getDocument().removeDocumentListener(documentListener);
        }
    }

    // return true if the operation was "completed"
    public boolean updateListSelectionFromEditor() {
        if (editor instanceof JTextComponent) {
            JTextComponent tf = (JTextComponent) editor;
            updateListSelectionFromEditor(tf);
            return true;
        }
        return false;
    }

    protected void updateListSelectionFromEditor(@NotNull JTextComponent editor) {
        String text = editor.getText();
        ListModel<Object> model = listBox.getModel();
        int items = model.getSize();
        for (int i = 0; i < items; i++) {
            Object element = model.getElementAt(i);
            if (element == null) continue;

            String asString = element.toString();
            if (asString == null || !asString.equals(text)) continue;

            JList<?> list = popup.getList();
            list.setSelectedIndex(i);
            list.ensureIndexIsVisible(i);
            return;
        }

        popup.getList().clearSelection();
    }

    /**
     * Provide style related information to a cell renderer.
     */
    protected void updateRendererStyle(@NotNull Component c) {
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            BasicContextualColors colors = getRendererStyleColors();
            jc.putClientProperty(AquaColors.COMPONENT_COLORS_KEY, colors);
        }
    }

    /**
     * Provide style related information to a cell editor.
     */
    protected void updateEditorStyle(@NotNull Component c) {
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            BasicContextualColors colors = getEditorStyleColors();
            jc.putClientProperty(AquaColors.COMPONENT_COLORS_KEY, colors);
        }
    }

    /**
     * Return style dependent colors for rendering.
     */
    protected @NotNull BasicContextualColors getRendererStyleColors() {
        if (isToolbar) {
            return AquaColors.TEXTURED_TOOLBAR_COLORS;
        } else if (isTextured) {
            return AquaColors.TEXTURED_COLORS;
        } else {
            return AquaColors.CLEAR_CONTROL_COLORS;
        }
    }

    /**
     * Return style dependent colors for editing.
     */
    protected @NotNull BasicContextualColors getEditorStyleColors() {
        if (isToolbar) {
            return AquaColors.TEXTURED_TOOLBAR_COLORS;
        } else if (isTextured) {
            return AquaColors.TEXTURED_COLORS;
        } else if (cellStatus == AquaCellEditorPolicy.CellStatus.CELL_EDITOR) {
            return AquaColors.CELL_TEXT_COLORS;
        } else {
            return AquaColors.CLEAR_CONTROL_COLORS;
        }
    }

    final class AquaComboBoxEditorUI extends AquaTextFieldUI implements FocusRingOutlineProvider {

        @Override
        public void installUI(@NotNull JComponent c) {
            // This override is needed only because of a "temporary workaround" in BasicTextUI.installUI that updates
            // the background after installDefaults has been performed. The same workaround is performed by the
            // BasicTextUI property change listener for edited and enabled.

            super.installUI(c);
            assert appearanceContext != null;
            AquaColors.installColors(editor, appearanceContext, colors);
            editor.repaint();
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            JTextComponent c = getComponent();
            Border b = c.getBorder();
            if (b == null || b instanceof UIDefaults) {
                c.setBorder(null);
            }
        }

        @Override
        public void updateStyle() {
            super.updateStyle();
            updateEditorStyle();
        }

        protected void updateEditorStyle() {
            AquaComboBoxUI.this.updateEditorStyle(editor);
        }

        @Override
        protected void paintBackgroundSafely(@NotNull Graphics g, @Nullable Color background) {
            int width = editor.getWidth();
            int height = editor.getHeight();

            if (background != null && background.getAlpha() > 0) {
                g.setColor(background);
                g.fillRect(0, 0, width, height);
            }
        }

        @Override
        public int getTextMargin()
        {
            return 3;
        }

        @Override
        public @Nullable Shape getFocusRingOutline(@NotNull JComponent c) {
            Component parent = c.getParent();
            if (parent instanceof JComboBox) {
                JComboBox<?> cb = (JComboBox) parent;

                // The focus ring for a combo box goes around the entire combo box, not the text field.

                AquaComboBoxUI ui = AquaUtils.getUI(cb, AquaComboBoxUI.class);
                if (ui != null) {
                    // Translate the shape into the text field coordinate space.
                    // Can't use AffineTransform.createTransformedShape() because that probably returns a Path,
                    // losing useful information.
                    Shape s = ui.getFocusRingOutline(cb);
                    if (s != null) {
                        Rectangle textFieldBounds = c.getBounds();
                        return ExpandableOutline.createTranslatedShape(s, -textFieldBounds.x, -textFieldBounds.y);
                    }
                }

                int width = cb.getWidth();
                int height = cb.getHeight();
                Rectangle textFieldBounds = c.getBounds();
                int x = -textFieldBounds.x;
                int y = -textFieldBounds.y;
                return new RoundRectangle2D.Double(x+AquaButtonUI.OUTLINE_OFFSET, y+AquaButtonUI.OUTLINE_OFFSET,
                        width-2*AquaButtonUI.OUTLINE_OFFSET, height-2*AquaButtonUI.OUTLINE_OFFSET,
                        AquaButtonUI.OUTLINE_CORNER, AquaButtonUI.OUTLINE_CORNER);
            }

            return null;
        }
    }

    final class AquaComboBoxEditor extends BasicComboBoxEditor implements UIResource {

        AquaComboBoxEditor() {
        }

        @Override
        protected @NotNull JTextField createEditorComponent() {
            return new AquaCustomComboTextField();
        }
    }

    private class MyDocumentListener implements DocumentListener {
        @Override
        public void insertUpdate(@NotNull DocumentEvent e)
        {
            editorTextChanged();
        }

        @Override
        public void removeUpdate(@NotNull DocumentEvent e)
        {
            editorTextChanged();
        }

        @Override
        public void changedUpdate(@NotNull DocumentEvent e)
        {
            editorTextChanged();
        }
    }

    protected void editorTextChanged() {
        if (popup.isVisible()) {
            if (!isTextured) {
                updateListSelectionFromEditor((JTextComponent) editor);
            }
        }
    }

    @SuppressWarnings("serial") // Superclass is not serializable across versions
    class AquaCustomComboTextField extends JTextField {
        @SuppressWarnings("serial") // anonymous class

        public AquaCustomComboTextField() {

            setUI(new AquaComboBoxEditorUI());

            setBorder(null);

            // TBD: there should be some space at the left and right ends of the text, but JTextField does not support that

            //setBackground(new Color(255, 200, 0, 128)); // debug

            InputMap inputMap = getInputMap();
            inputMap.put(KeyStroke.getKeyStroke("DOWN"), highlightNextAction);
            inputMap.put(KeyStroke.getKeyStroke("KP_DOWN"), highlightNextAction);
            inputMap.put(KeyStroke.getKeyStroke("UP"), highlightPreviousAction);
            inputMap.put(KeyStroke.getKeyStroke("KP_UP"), highlightPreviousAction);

            inputMap.put(KeyStroke.getKeyStroke("HOME"), highlightFirstAction);
            inputMap.put(KeyStroke.getKeyStroke("END"), highlightLastAction);
            inputMap.put(KeyStroke.getKeyStroke("PAGE_UP"), highlightPageUpAction);
            inputMap.put(KeyStroke.getKeyStroke("PAGE_DOWN"), highlightPageDownAction);

            Action action = getActionMap().get(JTextField.notifyAction);
            inputMap.put(KeyStroke.getKeyStroke("ENTER"), new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    if (popup.isVisible()) {
                        triggerSelectionEvent(comboBox, cellStatus, e);

                        if (editor instanceof AquaCustomComboTextField) {
                            ((AquaCustomComboTextField)editor).selectAll();
                        }
                    }
                    action.actionPerformed(e);
                }
            });
        }

        @Override
        public @Nullable Color getForeground() {
            if (!hasFocus && cellStatus != null) {
                return comboBox.getForeground();
            }
            return super.getForeground();
        }

        // workaround for 4530952
        public void setText(@Nullable String s) {
            if (getText().equals(s)) {
                return;
            }
            super.setText(s);
        }
    }

    protected @NotNull FocusListener createFocusListener() {

        // Note that this listener is attached to the combo box and the combo box editor component.

        return new BasicComboBoxUI.FocusHandler() {

            @Override
            public void focusGained(FocusEvent e) {

                if ((editor != null) && (e.getSource() == editor)) {
                    if (arrowButton != null) {
                        arrowButton.repaint();
                    }
                }

                super.focusGained(e);
            }

            @Override
            public void focusLost(FocusEvent e) {

                if ((editor != null) && (e.getSource() == editor)) {
                    if (arrowButton != null) {
                        arrowButton.repaint();
                    }
                }

                // TBD: According to the original comment,
                // this override is necessary because the Basic L&F for the combo box is working
                // around a Solaris-only bug that we don't have on Mac OS X.  So, remove the lightweight
                // popup check here. rdar://Problem/3518582.
                // However, the current code in BasicComboBoxUI is quite different and does not have a lightweight
                // popup check.
                // Therefore, do not override.

//                hasFocus = false;
//                if (!e.isTemporary()) {
//                    setPopupVisible(comboBox, false);
//                }
//                comboBox.repaint();
//
//                // Notify assistive technologies that the combo box lost focus
//                AccessibleContext ac = ((Accessible)comboBox).getAccessibleContext();
//                if (ac != null) {
//                    ac.firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY, AccessibleState.FOCUSED, null);
//                }

                super.focusLost(e);
            }
        };
    }

    protected void installKeyboardActions() {
        super.installKeyboardActions();

        ActionMap actionMap = new ActionMapUIResource();

        actionMap.put("aquaSelectNext", highlightNextAction);
        actionMap.put("aquaSelectPrevious", highlightPreviousAction);
        actionMap.put("enterPressed", triggerSelectionAction);
        actionMap.put("aquaSpacePressed", toggleSelectionAction);

        actionMap.put("aquaSelectHome", highlightFirstAction);
        actionMap.put("aquaSelectEnd", highlightLastAction);
        actionMap.put("aquaSelectPageUp", highlightPageUpAction);
        actionMap.put("aquaSelectPageDown", highlightPageDownAction);

        actionMap.put("aquaHidePopup", hideAction);

        SwingUtilities.replaceUIActionMap(comboBox, actionMap);
    }

    @SuppressWarnings("serial") // Superclass is not serializable across versions
    private abstract class ComboBoxAction extends AbstractAction {
        public void actionPerformed(@NotNull ActionEvent e) {
            if (!comboBox.isEnabled() || !comboBox.isShowing()) {
                return;
            }

            if (comboBox.isPopupVisible()) {
                AquaComboBoxUI ui = (AquaComboBoxUI)comboBox.getUI();
                performComboBoxAction(ui);
            } else {
                comboBox.setPopupVisible(true);
            }
        }

        abstract void performComboBoxAction(@NotNull AquaComboBoxUI ui);
    }

    /**
     * Hilight _but do not select_ the next item in the list.
     */
    @SuppressWarnings("serial") // anonymous class
    private final @NotNull Action highlightNextAction = new ComboBoxAction() {
        @Override
        public void performComboBoxAction(@NotNull AquaComboBoxUI ui) {
            int si = listBox.getSelectedIndex();

            if (si < comboBox.getModel().getSize() - 1) {
                listBox.setSelectedIndex(si + 1);
                listBox.ensureIndexIsVisible(si + 1);
            }
            comboBox.repaint();
        }
    };

    /**
     * Hilight _but do not select_ the previous item in the list.
     */
    @SuppressWarnings("serial") // anonymous class
    private final @NotNull Action highlightPreviousAction = new ComboBoxAction() {
        @Override
        void performComboBoxAction(@NotNull AquaComboBoxUI ui) {
            int si = listBox.getSelectedIndex();
            if (si > 0) {
                listBox.setSelectedIndex(si - 1);
                listBox.ensureIndexIsVisible(si - 1);
            }
            comboBox.repaint();
        }
    };

    @SuppressWarnings("serial") // anonymous class
    private final @NotNull Action highlightFirstAction = new ComboBoxAction() {
        @Override
        void performComboBoxAction(@NotNull AquaComboBoxUI ui) {
            listBox.setSelectedIndex(0);
            listBox.ensureIndexIsVisible(0);
        }
    };

    @SuppressWarnings("serial") // anonymous class
    private final @NotNull Action highlightLastAction = new ComboBoxAction() {
        @Override
        void performComboBoxAction(@NotNull AquaComboBoxUI ui) {
            int size = listBox.getModel().getSize();
            listBox.setSelectedIndex(size - 1);
            listBox.ensureIndexIsVisible(size - 1);
        }
    };

    @SuppressWarnings("serial") // anonymous class
    private final @NotNull Action highlightPageUpAction = new ComboBoxAction() {
        @Override
        void performComboBoxAction(@NotNull AquaComboBoxUI ui) {
            int current = listBox.getSelectedIndex();
            int first = listBox.getFirstVisibleIndex();

            if (current != first) {
                listBox.setSelectedIndex(first);
                return;
            }

            int page = listBox.getVisibleRect().height / listBox.getCellBounds(0, 0).height;
            int target = first - page;
            if (target < 0) target = 0;

            listBox.ensureIndexIsVisible(target);
            listBox.setSelectedIndex(target);
        }
    };

    @SuppressWarnings("serial") // anonymous class
    private final @NotNull Action highlightPageDownAction = new ComboBoxAction() {
        @Override
        void performComboBoxAction(@NotNull AquaComboBoxUI ui) {
            int current = listBox.getSelectedIndex();
            int last = listBox.getLastVisibleIndex();

            if (current != last) {
                listBox.setSelectedIndex(last);
                return;
            }

            int page = listBox.getVisibleRect().height / listBox.getCellBounds(0, 0).height;
            int end = listBox.getModel().getSize() - 1;
            int target = last + page;
            if (target > end) target = end;

            listBox.ensureIndexIsVisible(target);
            listBox.setSelectedIndex(target);
        }
    };

    // For <rdar://problem/3759984> Java 1.4.2_5: Serializing Swing components not working
    // Inner classes were using a this reference and then trying to serialize the AquaComboBoxUI
    // We shouldn't do that. But we need to be able to get the popup from other classes, so we need
    // a public accessor.
    public @Nullable ComboPopup getPopup() {
        // not null when the UI is installed
        return popup;
    }

    protected @NotNull LayoutManager createLayoutManager() {
        return new AquaComboBoxLayoutManager();
    }

    class AquaComboBoxLayoutManager extends BasicComboBoxUI.ComboBoxLayoutManager {

        public void layoutContainer(Container parent) {
            int width = comboBox.getWidth();
            int height = comboBox.getHeight();

            if (comboBox.isEditable()) {
                ComboBoxConfiguration g = (ComboBoxConfiguration) getConfiguration();
                assert g != null;
                AquaUtils.configure(painter, comboBox, width, height);
                if (editor != null) {
                    Rectangle editorBounds = AquaUtils.toMinimumRectangle(painter.getComboBoxEditorBounds(g));
                    editor.setBounds(editorBounds);
                }
                if (arrowButton != null) {
                    Rectangle arrowBounds = AquaUtils.toMinimumRectangle(painter.getComboBoxIndicatorBounds(g));
                    arrowButton.setBounds(arrowBounds);
                }
            } else {
                arrowButton.setBounds(0, 0, width, height);
            }
        }
    }

    public static @NotNull AquaComboBoxType getComboBoxType(JComboBox<?> c) {
        if (c.isEditable()) {
            return AquaComboBoxType.EDITABLE_COMBO_BOX;
        } else if (Boolean.TRUE.equals(c.getClientProperty(AquaComboBoxUI.POPDOWN_CLIENT_PROPERTY_KEY))) {
            return AquaComboBoxType.PULL_DOWN_MENU_BUTTON;
        } else {
            return AquaComboBoxType.POP_UP_MENU_BUTTON;
        }
    }

    protected static void triggerSelectionEvent(@NotNull JComboBox<?> comboBox,
                                                @Nullable AquaCellEditorPolicy.CellStatus cellStatus, ActionEvent e) {
        if (!comboBox.isEnabled()) {
            return;
        }

        AquaComboBoxUI ui = (AquaComboBoxUI)comboBox.getUI();
        ComboPopup popup = ui.getPopup();
        assert popup != null;
        if (popup.getList().getSelectedIndex() < 0) {
            comboBox.setPopupVisible(false);
        }

        if (cellStatus == null) {
            // The original code below from AquaComboBoxUI has the effect of setting the text to the empty string. Seems
            // like a bug. Instead, do what BasicComboBoxUI does:

            comboBox.setSelectedItem(comboBox.getSelectedItem());

//            // Forces the selection of the list item if the combo box is in a JTable
//            comboBox.setSelectedIndex(aquaUi.getPopup().getList().getSelectedIndex());

            return;
        }

        if (comboBox.isPopupVisible()) {
            comboBox.setSelectedIndex(ui.getPopup().getList().getSelectedIndex());
            comboBox.setPopupVisible(false);
            return;
        }

        // Call the default button binding.
        // This is a pretty messy way of passing an event through to the root pane
        JRootPane root = SwingUtilities.getRootPane(comboBox);
        if (root == null) return;

        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();
        if (im == null || am == null) return;

        Object obj = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        if (obj == null) return;

        Action action = am.get(obj);
        if (action == null) return;

        action.actionPerformed(new ActionEvent(root, e.getID(), e.getActionCommand(), e.getWhen(), e.getModifiers()));
    }

    // This is somewhat messy.  The difference here from BasicComboBoxUI.EnterAction is that
    // arrow up or down does not automatically select the
    @SuppressWarnings("serial") // anonymous class
    private final @NotNull Action triggerSelectionAction = new AbstractAction() {
        public void actionPerformed(ActionEvent e) {
            triggerSelectionEvent(comboBox, cellStatus, e);
        }

        @Override
        public boolean isEnabled() {
            return comboBox.isPopupVisible() && super.isEnabled();
        }
    };

    @SuppressWarnings("serial") // anonymous class
    private static final @NotNull Action toggleSelectionAction = new AbstractAction() {
        public void actionPerformed(ActionEvent e) {
            JComboBox<?> comboBox = (JComboBox<?>) e.getSource();
            if (!comboBox.isEnabled()) return;
            if (comboBox.isEditable()) return;

            AquaComboBoxUI ui = (AquaComboBoxUI)comboBox.getUI();

            if (comboBox.isPopupVisible()) {
                ComboPopup popup = ui.getPopup();
                assert popup != null;
                comboBox.setSelectedIndex(popup.getList().getSelectedIndex());
                comboBox.setPopupVisible(false);
                return;
            }

            comboBox.setPopupVisible(true);
        }
    };

    @SuppressWarnings("serial") // anonymous class
    private final @NotNull Action hideAction = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            JComboBox<?> comboBox = (JComboBox<?>) e.getSource();
            comboBox.firePopupMenuCanceled();
            comboBox.setPopupVisible(false);
        }

        @Override
        public boolean isEnabled() {
            return comboBox.isPopupVisible() && super.isEnabled();
        }
    };

    @Override
    public void applySizeFor(@NotNull JComponent c, @NotNull Size size, boolean isDefaultSize) {
        sizeVariant = size;
        if (isDefaultSize) {
            size = determineDefaultSize(size);
        }

        configure(size);
    }

    /**
     * Return the effective size variant.
     */
    public @NotNull Size getSizeVariant() {
        if (sizeVariant == null) {
            return Size.REGULAR;
        }

        // This code now assumes that the canonicalization of layout configurations will remove any unsupported sizes,
        // so no size conversion is needed here.

        return sizeVariant;
    }

    protected @NotNull Size determineDefaultSize(@NotNull Size size) {
        if (size == Size.REGULAR && cellStatus != null && comboBox.getHeight() < 16) {
            return Size.SMALL;
        }
        return size;
    }

    public @NotNull Dimension getMinimumSize(JComponent c) {
        if (isMinimumSizeDirty) {
            calculateLayoutSizes();
        }
        return new Dimension(cachedMinimumSize);
    }

    public @NotNull Dimension getPreferredSize(JComponent c) {
        if (isMinimumSizeDirty) {
            calculateLayoutSizes();
        }
        return new Dimension(cachedPreferredSize);
    }

    protected void calculateLayoutSizes() {
        AbstractComboBoxLayoutConfiguration g = getLayoutConfiguration();
        assert g != null;
        LayoutInfo layoutInfo = painter.getLayoutInfo().getLayoutInfo(g);
        int fixedRenderingHeight = (int) layoutInfo.getFixedVisualHeight();
        // The fixed rendering height is a minimum height.
        // It is the preferred height for all combo boxes except cell styles, where we add some extra space.
        // The native renderer will vertically center the cell arrows in the requested space.

        int minimumHeight = fixedRenderingHeight;
        int preferredHeight = fixedRenderingHeight;

        Dimension size = null;

        if (arrowButton != null) {
            if (g.isCell()) {
                preferredHeight += 6;
            }

            Insetter s = getContentInsets(g);
            if (s != null) {
                Dimension displaySize = getDisplaySize();
                size = s.expand(displaySize);
            }
        }

        if (size == null) {
            boolean editable = comboBox.isEditable();
            if (editable && arrowButton != null && editor != null) {
                size = super.getMinimumSize(comboBox);
                Insets margin = arrowButton.getMargin();
                size.height += margin.top + margin.bottom;
            } else {
                size = super.getMinimumSize(comboBox);
            }
        }

        if (fixedRenderingHeight == 0) {
            if (size.height > minimumHeight) {
                minimumHeight = size.height;
            }

            if (size.height > preferredHeight) {
                preferredHeight = size.height;
            }
        }

        cachedMinimumSize.setSize(size.width, minimumHeight);
        cachedPreferredSize.setSize(size.width, preferredHeight);
        isMinimumSizeDirty = false;
    }

    // Overridden to use the proper renderer
    @Override
    protected @NotNull Dimension getDefaultSize() {
        ListCellRenderer r = comboBox.getRenderer();
        if (r == null)  {
            r = new DefaultListCellRenderer();
        }
        try {
            Dimension d = getSizeForComponent(r.getListCellRendererComponent(listBox, " ", -1, false, false));
            return new Dimension(d.width, d.height);
        } catch (RuntimeException ex) {
            return new Dimension(20, 20);
        }
    }

    protected void respondToHierarchyChange() {
        // Avoid losing the configuration of a combo box cell renderer/editor when the renderer/editor is installed in
        // the cell renderer pane or the cell container.

        if (cellStatus == null) {
            configure(null);
        }
    }

    /**
     * Style related configuration affecting layout
     *
     * @param size Optional new size variant to install
     */
    protected void configure(@Nullable Size size) {

        if (comboBox == null) {
            return;
        }

        if (size == null) {
            size = AquaUtilControlSize.getUserSizeFrom(comboBox);
        }
        sizeVariant = size;

        if (buttonRenderer != null) {
            buttonRenderer.setSize(size);
        }
        if (listRenderer != null) {
            listRenderer.setSize(size);
        }

        {
            Object o = comboBox.getClientProperty(AquaComboBoxUI.POPDOWN_CLIENT_PROPERTY_KEY);
            isPopDown = Boolean.TRUE.equals(o);
        }

        {
            String style = null;

            Object o = comboBox.getClientProperty(AquaComboBoxUI.STYLE_CLIENT_PROPERTY_KEY);
            if (o instanceof String) {
                style = (String) o;
            } else {
                o = comboBox.getClientProperty(AquaComboBoxUI.ISSQUARE_CLIENT_PROPERTY_KEY);
                if (Boolean.TRUE.equals(o)) {
                    // old client property gets the old square style
                    style = "old_square";
                }
            }

            if ("textured".equals(style) && isToolbar) {
                style = "textured-onToolbar";
            }

            this.style = style;
        }

        boolean isEditable = comboBox.isEditable();
        AquaUIPainter.UILayoutDirection ld = AquaUtils.getLayoutDirection(comboBox);

        cellStatus = determineCellStatus();

        if (isEditable) {
            ComboBoxWidget widget = determineComboBoxWidget(cellStatus);
            layoutConfiguration = new ComboBoxLayoutConfiguration(widget, sizeVariant, ld);
            colors = getRendererStyleColors();
            updateEditorStyle(editor);
        } else {
            PopupButtonWidget widget = determinePopupButtonWidget(cellStatus);
            layoutConfiguration = new PopupButtonLayoutConfiguration(widget, sizeVariant, ld);
            AquaButtonExtendedTypes.WidgetInfo info = AquaButtonExtendedTypes.getWidgetInfo(widget);
            colors = info.getColors();
        }

        if (AquaUtilControlSize.isOKToInstallDefaultFont(comboBox)) {
            Font df = getDefaultFont();
            comboBox.setFont(df);
        }

        isDefaultStyle = determineIsDefaultStyle();
        isTextured = determineIsTextured();
        configureAppearanceContext(null);
        comboBox.revalidate();
        comboBox.repaint();
        isMinimumSizeDirty = true;
    }

    /**
     * Return the layout configuration. A configuration is always defined while the UI is installed.
     */
    public @Nullable AbstractComboBoxLayoutConfiguration getLayoutConfiguration() {
        return layoutConfiguration;
    }

    protected @NotNull Font getDefaultFont() {
        Font font = comboBox.getFont();
        Object widget = getWidget();
        AbstractComboBoxLayoutConfiguration g = getLayoutConfiguration();
        assert g != null;
        Size size = g.getSize();
        return AquaButtonExtendedTypes.getFont(widget, size, font);
    }

    /**
     * Return the nominal Y offset of the popup relative to the top of the combo box.
     * The nominal offset may be replaced if there is not enough room on the screen.
     */
    public int getNominalPopupYOffset() {
        AquaComboBoxType type = getComboBoxType(comboBox);

        if (type == AquaComboBoxType.POP_UP_MENU_BUTTON) {
            // A pop up menu button wants the selected item to appear over the button.
            JList<Object> list = popup.getList();
            if (list != null) {
                int selectedIndex = comboBox.getSelectedIndex();
                if (selectedIndex >= 0) {
                    Rectangle cellBounds = list.getCellBounds(selectedIndex, selectedIndex);
                    Border border = AquaContextualPopup.getContextualMenuBorder();
                    Insets s = border.getBorderInsets(null);
                    return -(cellBounds.y + s.top);
                }
            }
        } else {
            Object widget = getWidget();
            AquaButtonExtendedTypes.WidgetInfo info = AquaButtonExtendedTypes.getWidgetInfo(widget);
            int bottomGap = info.getBottomMenuGap();
            if (OSXSystemProperties.OSVersion >= 1014 && isTextured) {
                // If no focus ring is shown, then we need less room.
                bottomGap -= 2;
            }
            return comboBox.getHeight() + bottomGap;
        }

        return comboBox.getHeight() + 2;
    }

    /**
     * Return the widget. A widget is always defined while the UI is installed.
     */
    protected @NotNull Object getWidget() {
        if (layoutConfiguration instanceof ComboBoxLayoutConfiguration) {
            ComboBoxLayoutConfiguration bg = (ComboBoxLayoutConfiguration) layoutConfiguration;
            return bg.getWidget();
        } else if (layoutConfiguration instanceof PopupButtonLayoutConfiguration) {
            PopupButtonLayoutConfiguration bg = (PopupButtonLayoutConfiguration) layoutConfiguration;
            return bg.getPopupButtonWidget();
        } else {
            return BUTTON_POP_UP;
        }
    }

    protected @NotNull ComboBoxWidget determineComboBoxWidget(@Nullable AquaCellEditorPolicy.CellStatus cellStatus) {
        if (cellStatus != null) {
            return BUTTON_COMBO_BOX_CELL;
        }

        if (style != null) {
            switch (style) {
                case "tableHeader":
                case "cell":
                case "borderless":
                    return BUTTON_COMBO_BOX_CELL;
                case "textured":
                    return BUTTON_COMBO_BOX_TEXTURED;
                case "textured-onToolbar":
                    return BUTTON_COMBO_BOX_TEXTURED_TOOLBAR;
            }
        }

        if (isToolbar) {
            return BUTTON_COMBO_BOX_TEXTURED_TOOLBAR;
        }

        return BUTTON_COMBO_BOX;
    }

    protected @NotNull PopupButtonWidget determinePopupButtonWidget(@Nullable AquaCellEditorPolicy.CellStatus cellStatus) {
        if (cellStatus != null) {
            return isPopDown ? BUTTON_POP_DOWN_CELL : BUTTON_POP_UP_CELL;
        }

        if (style != null) {
            switch (style) {
                case "tableHeader":
                case "cell":
                case "borderless":
                    return isPopDown ? BUTTON_POP_DOWN_CELL : BUTTON_POP_UP_CELL;
                case "square":
                    // Gradient is the new Square
                    return isPopDown ? BUTTON_POP_DOWN_GRADIENT : BUTTON_POP_UP_GRADIENT;
                case "old_square":
                    // Old API gets the old style (if available)
                    return isPopDown ? BUTTON_POP_DOWN_SQUARE : BUTTON_POP_UP_SQUARE;
                case "bevel":
                    return isPopDown ? BUTTON_POP_DOWN_BEVEL : BUTTON_POP_UP_BEVEL;
                case "roundRect":
                    return isPopDown ? BUTTON_POP_DOWN_ROUND_RECT : BUTTON_POP_UP_ROUND_RECT;
                case "recessed":
                    return isPopDown ? BUTTON_POP_DOWN_RECESSED : BUTTON_POP_UP_RECESSED;
                case "textured":
                    return isPopDown ? BUTTON_POP_DOWN_TEXTURED : BUTTON_POP_UP_TEXTURED;
                case "textured-onToolbar":
                    return isPopDown ? BUTTON_POP_DOWN_TEXTURED_TOOLBAR : BUTTON_POP_UP_TEXTURED_TOOLBAR;
                case "gradient":
                    return isPopDown ? BUTTON_POP_DOWN_GRADIENT : BUTTON_POP_UP_GRADIENT;
            }
        }

        if (isToolbar) {
            return isPopDown ? BUTTON_POP_DOWN_TEXTURED_TOOLBAR : BUTTON_POP_UP_TEXTURED_TOOLBAR;
        }

        return isPopDown ? BUTTON_POP_DOWN : BUTTON_POP_UP;
    }

    @Override
    public void toolbarStatusChanged(@NotNull JComponent c) {
        boolean b = AquaUtils.isOnToolbar(comboBox);
        if (b != isToolbar) {
            isToolbar = b;
            configure(null);
        }
    }

    /**
     * Return the offset needed to align a popup menu item label with the combo box button label.
     * @return the offset, or null if none.
     */
    public @Nullable Point getPopupButtonLabelOffset() {
        // For a pop up menu, the goal is for the menu item label to exactly overlay the combo box button label, at
        // least in the case where our default renderer is used. The correction factors are based on a number of
        // parameters, many of which are not currently accessible. We can get a good approximation with the following
        // values.

        // TBD: calculate exactly based on layout information

        int labelXOffset = 0;
        int labelYOffset = 0;

        AquaComboBoxType type = getComboBoxType(comboBox);
        if (type == AquaComboBoxType.POP_UP_MENU_BUTTON) {
            labelXOffset -= 8;
            labelYOffset = 1;

            Object w = getWidget();
            if (w != AquaUIPainter.PopupButtonWidget.BUTTON_POP_UP) {
                labelXOffset -= 2;
                labelYOffset = 2;
            }
        }

        return labelXOffset != 0 || labelYOffset != 0 ? new Point(labelXOffset, labelYOffset) : null;
    }

    @SuppressWarnings("unchecked")
    static final RecyclableSingleton<ClientPropertyApplicator<JComboBox<?>, AquaComboBoxUI>> APPLICATOR = new
            RecyclableSingleton<ClientPropertyApplicator<JComboBox<?>, AquaComboBoxUI>>() {
                @Override
                protected ClientPropertyApplicator<JComboBox<?>, AquaComboBoxUI> getInstance() {
                    return new ClientPropertyApplicator<JComboBox<?>, AquaComboBoxUI>(
//                new Property<AquaComboBoxUI>(AquaFocusHandler.FRAME_ACTIVE_PROPERTY) {
//                    public void applyProperty(AquaComboBoxUI target, Object value) {
//                        if (Boolean.FALSE.equals(value)) {
//                            if (target.comboBox != null) target.comboBox.hidePopup();
//                        }
//                        if (target.listBox != null) target.listBox.repaint();
//                        if (target.comboBox != null) {
//                            target.comboBox.repaint();
//                        }
//                    }
//                },
                            new Property<AquaComboBoxUI>("editable") {
                                public void applyProperty(AquaComboBoxUI target, Object value) {
                                    target.configure(null);
                                }
                            },
                            new Property<AquaComboBoxUI>("background") {
                                public void applyProperty(AquaComboBoxUI target, Object value) {
                                    Color color = (Color)value;
                                    if (target.arrowButton != null) target.arrowButton.setBackground(color);
                                    //if (target.listBox != null) target.listBox.setBackground(color);
                                }
                            },
                            new Property<AquaComboBoxUI>("foreground") {
                                public void applyProperty(AquaComboBoxUI target, Object value) {
                                    Color color = (Color)value;
                                    if (target.arrowButton != null) target.arrowButton.setForeground(color);
                                    //if (target.listBox != null) target.listBox.setForeground(color);
                                }
                            },
                            new Property<AquaComboBoxUI>(POPDOWN_CLIENT_PROPERTY_KEY) {
                                public void applyProperty(AquaComboBoxUI target, Object value) {
                                    target.configure(null);
                                }
                            },
                            new Property<AquaComboBoxUI>(ISSQUARE_CLIENT_PROPERTY_KEY) {
                                public void applyProperty(AquaComboBoxUI target, Object value) {
                                    target.configure(null);
                                }
                            },
                            new Property<AquaComboBoxUI>(STYLE_CLIENT_PROPERTY_KEY) {
                                public void applyProperty(AquaComboBoxUI target, Object value) {
                                    target.configure(null);
                                }
                            },
                            new Property<AquaComboBoxUI>(TITLE_CLIENT_PROPERTY_KEY) {
                                public void applyProperty(AquaComboBoxUI target, Object value) {
                                    if (target.comboBox != null) {
                                        AquaComboBoxType type = getComboBoxType(target.comboBox);
                                        if (type == AquaComboBoxType.PULL_DOWN_MENU_BUTTON) {
                                            target.comboBox.setPrototypeDisplayValue(value);
                                            target.comboBox.repaint();
                                        }
                                    }
                                }
                            }
                    ) {
                        public AquaComboBoxUI convertJComponentToTarget(JComboBox<?> combo) {
                            ComboBoxUI comboUI = combo.getUI();
                            if (comboUI instanceof AquaComboBoxUI) return (AquaComboBoxUI)comboUI;
                            return null;
                        }
                    };
                }
            };

    static @NotNull ClientPropertyApplicator<JComboBox<?>,AquaComboBoxUI> getApplicator() {
        return APPLICATOR.get();
    }
}
