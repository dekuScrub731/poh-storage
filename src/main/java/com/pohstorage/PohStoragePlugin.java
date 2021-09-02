package com.pohstorage;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import java.util.*;

import static com.pohstorage.PohStorageConfig.preserveFilters.*;

@Slf4j
@PluginDescriptor(
		name = "POH Storage",
		description = "Filter and configure POH Storage",
		tags = {"poh","storage","filter"}
)
public class PohStoragePlugin extends Plugin
{
	private boolean showEmpty = true;
	private boolean showPartial = true;
	private boolean showFull = true;
	private int originalScroll;
	private Widget emptyCheck, partialCheck, fullCheck, emptyTitle, partialTitle, fullTitle;
	private List <PohStorageSet> storageSets = new ArrayList<PohStorageSet>();
	private List<PohStorageWidget> junkWidgets = new ArrayList<PohStorageWidget>();
	private List<Widget> addedDividers = new ArrayList<Widget>();

	private final String CONFIG_GROUP = "pohstorage";
	private final String CONTROL_ACTION = "Toggle";
	private final String TITLE_REGEX = "[\\(|:]";
	private final List<String> TITLES = Arrays.asList("Armour Case", "Cape Rack", "Toy Box", "Fancy Dress Box", "Treasure Chest", "Magic Wardrobe");

	private final int CONTAINER_HEIGHT_ADJUSTMENT = 25;

	private final int CONTROL_LABEL_COLOR = 0xcfcfcf;
	private final int CONTROL_Y = 42;
	private final int CONTROL_TITLE_WIDTH = 37;
	private final int CONTROL_TITLE_HEIGHT = 15;
	private final int CONTROL_CHECKBOX_WIDTH = 16;
	private final int CONTROL_CHECKBOX_HEIGHT = 16;
	private final int CONTROL_SPACING = 175;

	private final int WIDGET_DIVIDER_WIDTH = 16;
	private final int WIDGET_ICON_WIDTH = 36;
	private final int WIDGET_ICON_TOP_OFFSET = 20;
	private final int WIDGET_SET_WIDTH = 210;
	private final int WIDGET_SET_HEIGHT = 60;
	private final int WIDGET_OFFSET = 3;
	private final int MAX_HALF_SET = 4;

	private final int SET_TYPE_EMPTY = 0;
	private final int SET_TYPE_PARTIAL = 1;
	private final int SET_TYPE_FULL = 2;

	//Eventually submit a PR to add these to WidgetID and WidgetInfo
	private final int STORAGE_GROUP_ID = 675;
	private final int STORAGE_CONTAINER = 1;
	private final int STORAGE_TITLE_CONTAINER = 2;
	private final int STORAGE_CONTENT_CONTAINER = 3;
	private final int STORAGE_ITEM_CONTAINER = 4;
	private final int STORAGE_SCROLLBAR = 5;
	private final int STORAGE_RIGHT_CONTAINER = 6;
	private final int STORAGE_TIERS_BUTTON = 7;
	private final int STORAGE_DEPOSIT_MODE_BUTTON = 8;
	private final int STORAGE_SEARCH_BUTTON = 9;
	private final int STORAGE_DEPOSIT_INVENTORY_BUTTON = 10;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PohStorageConfig config;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("POH Storage Started!");
		loadConfig();
		if (pohStorageLoaded()) {
			clientThread.invokeLater(() ->
			{
				if (titleCheck()) {
					originalScroll = client.getWidget(STORAGE_GROUP_ID, STORAGE_ITEM_CONTAINER).getScrollHeight();
					applyChanges();
					updateWidgetHeight(CONTAINER_HEIGHT_ADJUSTMENT);
					addControls();
				}
			});
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("POH Storage Stopped!");
		if (pohStorageLoaded()) {
			clientThread.invokeLater(() ->
			{
				if (titleCheck()) {
					resetWidgets();
					removeControls();
					updateWidgetHeight(-1 * CONTAINER_HEIGHT_ADJUSTMENT);
					emptyCheck = null;
					partialCheck = null;
					fullCheck = null;
					emptyTitle = null;
					partialTitle = null;
					fullTitle = null;
				}
			});
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == STORAGE_GROUP_ID && pohStorageLoaded()) {
			clientThread.invokeLater(() ->
			{
				if (titleCheck()) {
					originalScroll = client.getWidget(STORAGE_GROUP_ID, STORAGE_ITEM_CONTAINER).getScrollHeight();
					applyChanges();
					updateWidgetHeight(CONTAINER_HEIGHT_ADJUSTMENT);
					addControls();
				}
			});
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == STORAGE_GROUP_ID && pohStorageLoaded()) {
			emptyCheck = null;
			partialCheck = null;
			fullCheck = null;
			emptyTitle = null;
			partialTitle = null;
			fullTitle = null;

			if (config.preserveFilters() == NEVER) {
				showEmpty = true;
				showPartial = true;
				showFull = true;
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (pohStorageLoaded() && event.getContainerId() == InventoryID.INVENTORY.getId()) {
			clientThread.invokeLater(() ->
			{
				if (titleCheck())
					applyChanges();
			});
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (pohStorageLoaded() && configChanged.getGroup().equals(CONFIG_GROUP) && !configChanged.getKey().equals("showEmptySets") && !configChanged.getKey().equals("showPartialSets") && !configChanged.getKey().equals("showFullSets"))
			clientThread.invokeLater(() ->
			{
				if (titleCheck()) {
					applyChanges();
				}
			});
		else if (configChanged.getGroup().equals(CONFIG_GROUP) && configChanged.getKey().equals("preserveFilters") && configChanged.getNewValue().equals("Never")) {
			showEmpty = true;
			showPartial = true;
			showFull = true;
		}
	}

	@Provides
	PohStorageConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PohStorageConfig.class);
	}

	public void loadConfig() {
		if (config.preserveFilters() == ACROSS_SESSIONS) {
			showEmpty = config.showEmptySets();
			showPartial = config.showPartialSets();
			showFull = config.showFullSets();
		}
	}

	public void applyChanges() {
		resetWidgets();
		updateWidgetLists();
		adjustWidgets();
	}

	private boolean pohStorageLoaded() {
		return client.getWidget(STORAGE_GROUP_ID, STORAGE_TITLE_CONTAINER) != null && client.getWidget(STORAGE_GROUP_ID, STORAGE_ITEM_CONTAINER) != null;
	}

	private boolean titleCheck() {
		return TITLES.contains(client.getWidget(STORAGE_GROUP_ID, STORAGE_TITLE_CONTAINER).getDynamicChildren()[1].getText().split(TITLE_REGEX)[0].trim());
	}

	private void updateWidgetHeight(int adjustment) {
		Widget contentContainer = client.getWidget(STORAGE_GROUP_ID, STORAGE_CONTENT_CONTAINER);
		contentContainer.setOriginalHeight(contentContainer.getOriginalHeight() + adjustment);
		contentContainer.revalidate();

		client.getWidget(STORAGE_GROUP_ID, STORAGE_ITEM_CONTAINER).revalidate();

		Widget scrollbar = client.getWidget(STORAGE_GROUP_ID, STORAGE_SCROLLBAR);
		scrollbar.revalidate();
		for (Widget scrollChild : scrollbar.getDynamicChildren())
			scrollChild.revalidate();
	}

	private void resetWidgets() {
		for (PohStorageSet storageSet : storageSets) {
			for (PohStorageWidget storageWidget : storageSet.getAll()) {
				storageWidget.getWidget().setOriginalX(storageWidget.getOriginalX());
				storageWidget.getWidget().setOriginalY(storageWidget.getOriginalY());
				storageWidget.getWidget().setHidden(storageWidget.isOriginalHidden());

				if (!storageWidget.isIcon()) {
					storageWidget.getWidget().setOpacity(storageWidget.getOriginalOpacity());
					storageWidget.getWidget().setSpriteId(storageWidget.getOriginalSpriteId());
					storageWidget.getWidget().setOriginalWidth(storageWidget.getOriginalWidth());
					storageWidget.getWidget().setWidthMode(storageWidget.getOriginalWidthMode());
					storageWidget.getWidget().setXPositionMode(storageWidget.getOriginalXPositionMode());
				}

				storageWidget.getWidget().revalidate();
			}
		}

		for (PohStorageWidget junk: junkWidgets) {
			junk.getWidget().setOriginalX(junk.getOriginalX());
			junk.getWidget().revalidate();
		}

		for (Widget addedDivider: addedDividers) {
			addedDivider.setHidden(true);
			addedDivider.revalidate();
		}

		Widget container = client.getWidget(STORAGE_GROUP_ID, STORAGE_ITEM_CONTAINER);

		container.setScrollHeight(originalScroll);
		container.revalidateScroll();

		client.runScript(
				ScriptID.UPDATE_SCROLLBAR,
				STORAGE_GROUP_ID << 16 | STORAGE_SCROLLBAR,
				STORAGE_GROUP_ID << 16 | STORAGE_ITEM_CONTAINER,
				container.getScrollY()
		);
	}

	private void updateWidgetLists() {
		storageSets = new ArrayList<PohStorageSet>();
		junkWidgets = new ArrayList<PohStorageWidget>();
		addedDividers = new ArrayList<Widget>();

		for (Widget widgetItem : client.getWidget(STORAGE_GROUP_ID, STORAGE_ITEM_CONTAINER).getDynamicChildren()) {
			if (widgetItem.getType()==WidgetType.TEXT) {
				storageSets.add(newPohStorageSet(widgetItem));
			}
			else if (widgetItem.getWidth()==WIDGET_DIVIDER_WIDTH) {
				junkWidgets.add(newPohStorageWidget(widgetItem));
				widgetItem.setOriginalX(-100);
				widgetItem.revalidate();
			}
		}
	}

	private void addControls() {
		if (emptyTitle == null || emptyCheck == null) {
			emptyTitle = addControlTitle(client.getWidget(STORAGE_GROUP_ID, STORAGE_TITLE_CONTAINER), SET_TYPE_EMPTY, "Empty Sets");
			emptyCheck = addControlCheckbox(client.getWidget(STORAGE_GROUP_ID, STORAGE_TITLE_CONTAINER), SET_TYPE_EMPTY, "Empty Sets", showEmpty);
		}
		else {
			emptyTitle.setHidden(false);
			emptyCheck.setHidden(false);
		}

		if (partialTitle == null || partialCheck == null) {
			partialTitle = addControlTitle(client.getWidget(STORAGE_GROUP_ID, STORAGE_TITLE_CONTAINER), SET_TYPE_PARTIAL, "Partial Sets");
			partialCheck = addControlCheckbox(client.getWidget(STORAGE_GROUP_ID, STORAGE_TITLE_CONTAINER), SET_TYPE_PARTIAL, "Partial Sets", showPartial);
		}
		else {
			partialTitle.setHidden(false);
			partialCheck.setHidden(false);
		}

		if (fullTitle == null || fullCheck == null) {
			fullTitle = addControlTitle(client.getWidget(STORAGE_GROUP_ID, STORAGE_TITLE_CONTAINER), SET_TYPE_FULL, "Full Sets");
			fullCheck = addControlCheckbox(client.getWidget(STORAGE_GROUP_ID, STORAGE_TITLE_CONTAINER), SET_TYPE_FULL, "Full Sets", showFull);
		}
		else {
			fullTitle.setHidden(false);
			fullCheck.setHidden(false);
		}
	}

	private void removeControls() {
		emptyTitle.setHidden(true);
		partialTitle.setHidden(true);
		fullTitle.setHidden(true);
		emptyCheck.setHidden(true);
		partialCheck.setHidden(true);
		fullCheck.setHidden(true);
	}

	private void toggle(Widget toggle, String tooltip, boolean show) {
		if (show) {
			toggle.setOnMouseOverListener((JavaScriptCallback) ev -> toggle.setSpriteId(SpriteID.SQUARE_CHECK_BOX_HOVERED));
			toggle.setOnMouseLeaveListener((JavaScriptCallback) ev -> toggle.setSpriteId(SpriteID.SQUARE_CHECK_BOX));
			toggle.setSpriteId(SpriteID.SQUARE_CHECK_BOX);
		}
		else {
			toggle.setOnMouseOverListener((JavaScriptCallback) ev -> toggle.setSpriteId(SpriteID.SQUARE_CHECK_BOX_CHECKED_HOVERED));
			toggle.setOnMouseLeaveListener((JavaScriptCallback) ev -> toggle.setSpriteId(SpriteID.SQUARE_CHECK_BOX_CHECKED));
			toggle.setSpriteId(SpriteID.SQUARE_CHECK_BOX_CHECKED);
		}

		if (tooltip.equals("Empty Sets")) {
			showEmpty = !showEmpty;
			config.showEmptySets(showEmpty);
		}
		else if (tooltip.equals("Partial Sets")) {
			showPartial = !showPartial;
			config.showPartialSets(showPartial);
		}
		else if (tooltip.equals("Full Sets")) {
			showFull = !showFull;
			config.showFullSets(showFull);
		}

		toggle.setOnOpListener((JavaScriptCallback) ev -> toggle(toggle, tooltip, !show));
		applyChanges();
	}

	private Widget addControlTitle(Widget parentWidget, int index, String title) {
		Widget titleWidget = parentWidget.createChild(-1, WidgetType.TEXT);
		titleWidget.setText(title);
		titleWidget.setOriginalWidth(CONTROL_TITLE_WIDTH);
		titleWidget.setOriginalHeight(CONTROL_TITLE_HEIGHT);
		titleWidget.setFontId(FontID.PLAIN_11);
		titleWidget.setTextColor(CONTROL_LABEL_COLOR);
		titleWidget.setTextShadowed(true);
		titleWidget.setOriginalY(CONTROL_Y);
		titleWidget.setOriginalX(index * CONTROL_SPACING + 31);
		titleWidget.revalidate();
		return titleWidget;
	}

	private Widget addControlCheckbox(Widget parentWidget, int index, String tooltip, boolean show) {
		Widget checkWidget = parentWidget.createChild(-1, WidgetType.GRAPHIC);
		checkWidget.setOriginalWidth(CONTROL_CHECKBOX_WIDTH);
		checkWidget.setOriginalHeight(CONTROL_CHECKBOX_HEIGHT);
		checkWidget.setOriginalY(CONTROL_Y - 2);
		checkWidget.setOriginalX(index * CONTROL_SPACING + 11);
		checkWidget.setName(tooltip);
		checkWidget.setAction(1, CONTROL_ACTION);

		checkWidget.setHasListener(true);
		checkWidget.setOnOpListener((JavaScriptCallback) ev -> toggle(checkWidget, tooltip, show));
		if (show) {
			checkWidget.setSpriteId(SpriteID.SQUARE_CHECK_BOX_CHECKED);
			checkWidget.setOnMouseOverListener((JavaScriptCallback) ev -> checkWidget.setSpriteId(SpriteID.SQUARE_CHECK_BOX_CHECKED_HOVERED));
			checkWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> checkWidget.setSpriteId(SpriteID.SQUARE_CHECK_BOX_CHECKED));
		}
		else {
			checkWidget.setSpriteId(SpriteID.SQUARE_CHECK_BOX);
			checkWidget.setOnMouseOverListener((JavaScriptCallback) ev -> checkWidget.setSpriteId(SpriteID.SQUARE_CHECK_BOX_HOVERED));
			checkWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> checkWidget.setSpriteId(SpriteID.SQUARE_CHECK_BOX));
		}

		checkWidget.revalidate();

		return checkWidget;
	}

	private void addDivider(int y) {
		Widget parent = client.getWidget(STORAGE_GROUP_ID, STORAGE_ITEM_CONTAINER);
		Widget child = parent.createChild(-1, WidgetType.GRAPHIC);
		child.setOriginalWidth(WIDGET_DIVIDER_WIDTH);
		child.setOriginalHeight(WIDGET_SET_HEIGHT - WIDGET_OFFSET);
		child.setOriginalX(WIDGET_SET_WIDTH - WIDGET_DIVIDER_WIDTH);
		child.setOriginalY(y);
		child.setSpriteId(SpriteID.UNKNOWN_BORDER_EDGE_VERTICAL);
		child.setSpriteTiling(true);
		child.revalidate();
		addedDividers.add(child);
	}

	private void adjustWidgets() {
		int normalOpacity = config.shadeOpacity();
		int hoverOpacity = (normalOpacity>20) ? normalOpacity - 20 : 0;
		int x=0;
		int top=0;

		List <PohStorageSet> hideSets = new ArrayList <PohStorageSet>();
		List <PohStorageSet> adjustSets = new ArrayList <PohStorageSet>();

		for (PohStorageSet storageSet : storageSets) {
			if((storageSet.getType()==SET_TYPE_EMPTY && !showEmpty) || (storageSet.getType()==SET_TYPE_PARTIAL && !showPartial) || (storageSet.getType()==SET_TYPE_FULL && !showFull))
				hideSets.add(storageSet);
			else
				adjustSets.add(storageSet);
		}

		Collections.sort(adjustSets);

		// Hide unwanted widgets
		for (PohStorageSet storageSet : hideSets) {
			for (Widget hideWidget : storageSet.getAllWidgets()) {
				hideWidget.setHidden(true);
			}
		}

		// Adjust others
		for (int i=0; i<adjustSets.size(); i++) {
			int finalI = i;

			top = x * WIDGET_SET_HEIGHT;
			x++;

			if (i>0 && adjustSets.get(i-1).isCollapsible() && adjustSets.get(i-1).getColumn()==1 && adjustSets.get(i).isCollapsible()) {

				// Right Half
				adjustSets.get(i).setColumn(2);
				x--;
				top -= WIDGET_SET_HEIGHT;

				adjustSets.get(i).getOutline().getWidget().setOriginalWidth(WIDGET_SET_WIDTH);
				adjustSets.get(i).getOutline().getWidget().setWidthMode(WidgetSizeMode.MINUS);
				adjustSets.get(i).getOutline().getWidget().setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);

				adjustSets.get(i).getHeader().getWidget().setOriginalWidth(WIDGET_SET_WIDTH - WIDGET_OFFSET);
				adjustSets.get(i).getHeader().getWidget().setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				adjustSets.get(i).getHeader().getWidget().setWidthMode(WidgetSizeMode.ABSOLUTE);
				adjustSets.get(i).getHeader().getWidget().setOriginalX(WIDGET_SET_WIDTH + WIDGET_OFFSET);

				adjustSets.get(i).getFooter().getWidget().setOriginalWidth(WIDGET_SET_WIDTH);
				adjustSets.get(i).getFooter().getWidget().setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
				adjustSets.get(i).getFooter().getWidget().setWidthMode(WidgetSizeMode.MINUS);

				adjustSets.get(i).getArrow().getWidget().setOriginalX(WIDGET_OFFSET);

				shiftIcons(adjustSets.get(i).getWidgetItems(), WIDGET_SET_WIDTH + WIDGET_OFFSET, top + WIDGET_ICON_TOP_OFFSET);
				addDivider(top);

			}
			else if (i<adjustSets.size()-1 && adjustSets.get(i).isCollapsible() && adjustSets.get(i+1).isCollapsible()) {

				// Left Half
				adjustSets.get(i).getOutline().getWidget().setOriginalWidth(WIDGET_SET_WIDTH);
				adjustSets.get(i).getOutline().getWidget().setWidthMode(WidgetSizeMode.ABSOLUTE);
				adjustSets.get(i).getOutline().getWidget().setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);

				adjustSets.get(i).getHeader().getWidget().setOriginalWidth(WIDGET_SET_WIDTH - WIDGET_OFFSET);
				adjustSets.get(i).getHeader().getWidget().setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				adjustSets.get(i).getHeader().getWidget().setWidthMode(WidgetSizeMode.ABSOLUTE);
				adjustSets.get(i).getHeader().getWidget().setOriginalX(WIDGET_OFFSET);

				adjustSets.get(i).getFooter().getWidget().setOriginalWidth(WIDGET_SET_WIDTH);
				adjustSets.get(i).getFooter().getWidget().setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				adjustSets.get(i).getFooter().getWidget().setWidthMode(WidgetSizeMode.ABSOLUTE);

				adjustSets.get(i).getArrow().getWidget().setOriginalX(WIDGET_SET_WIDTH + 2 * WIDGET_OFFSET);

				shiftIcons(adjustSets.get(i).getWidgetItems(), WIDGET_OFFSET, top + WIDGET_ICON_TOP_OFFSET);

			}
			else {

				// Full Width
				adjustSets.get(i).getOutline().getWidget().setOriginalWidth(0);
				adjustSets.get(i).getOutline().getWidget().setWidthMode(WidgetSizeMode.MINUS);
				adjustSets.get(i).getOutline().getWidget().setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);

				adjustSets.get(i).getHeader().getWidget().setOriginalWidth(WIDGET_OFFSET);
				adjustSets.get(i).getHeader().getWidget().setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
				adjustSets.get(i).getHeader().getWidget().setWidthMode(WidgetSizeMode.MINUS);
				adjustSets.get(i).getHeader().getWidget().setOriginalX(0);

				adjustSets.get(i).getFooter().getWidget().setOriginalWidth(0);
				adjustSets.get(i).getFooter().getWidget().setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
				adjustSets.get(i).getFooter().getWidget().setWidthMode(WidgetSizeMode.MINUS);

				adjustSets.get(i).getArrow().getWidget().setOriginalX(WIDGET_OFFSET);

				shiftIcons(adjustSets.get(i).getWidgetItems(), WIDGET_OFFSET, top + WIDGET_ICON_TOP_OFFSET);

			}

			adjustSets.get(i).getOutline().getWidget().setOriginalY(top);

			if (adjustSets.get(i).getType()==SET_TYPE_EMPTY && config.emptySetColor().getSpriteId() != 0) {
				adjustSets.get(i).getOutline().getWidget().setSpriteId(config.emptySetColor().getSpriteId());
				adjustSets.get(i).getOutline().getWidget().setOpacity(normalOpacity);
			}
			else if (adjustSets.get(i).getType()==SET_TYPE_PARTIAL && config.partialSetColor().getSpriteId() != 0) {
				adjustSets.get(i).getOutline().getWidget().setSpriteId(config.partialSetColor().getSpriteId());
				adjustSets.get(i).getOutline().getWidget().setOpacity(normalOpacity);

				adjustSets.get(i).getOutline().getWidget().setOnMouseRepeatListener((JavaScriptCallback) ev -> adjustSets.get(finalI).getOutline().getWidget().setOpacity(hoverOpacity));
				adjustSets.get(i).getOutline().getWidget().setOnMouseLeaveListener((JavaScriptCallback) ev -> adjustSets.get(finalI).getOutline().getWidget().setOpacity(normalOpacity));
			}
			else if (adjustSets.get(i).getType()==SET_TYPE_FULL && config.fullSetColor().getSpriteId() != 0) {
				adjustSets.get(i).getOutline().getWidget().setSpriteId(config.fullSetColor().getSpriteId());
				adjustSets.get(i).getOutline().getWidget().setOpacity(normalOpacity);
				adjustSets.get(i).getOutline().getWidget().setOnMouseRepeatListener((JavaScriptCallback) ev -> adjustSets.get(finalI).getOutline().getWidget().setOpacity(hoverOpacity));
				adjustSets.get(i).getOutline().getWidget().setOnMouseLeaveListener((JavaScriptCallback) ev -> adjustSets.get(finalI).getOutline().getWidget().setOpacity(normalOpacity));
			}

			adjustSets.get(i).getOutline().getWidget().revalidate();

			adjustSets.get(i).getHeader().getWidget().setOriginalY(top);
			adjustSets.get(i).getHeader().getWidget().revalidate();

			adjustSets.get(i).getArrow().getWidget().setOriginalY(top+5);
			adjustSets.get(i).getArrow().getWidget().revalidate();

			adjustSets.get(i).getFooter().getWidget().setOriginalY(top+40);
			adjustSets.get(i).getFooter().getWidget().revalidate();
		}

		Widget container = client.getWidget(STORAGE_GROUP_ID, STORAGE_ITEM_CONTAINER);

		int y = x * WIDGET_SET_HEIGHT - WIDGET_OFFSET;
		if (container.getHeight()>y)
			y=0;

		container.setScrollHeight(y);
		container.revalidateScroll();

		client.runScript(
				ScriptID.UPDATE_SCROLLBAR,
				STORAGE_GROUP_ID << 16 | STORAGE_SCROLLBAR,
				STORAGE_GROUP_ID << 16 | STORAGE_ITEM_CONTAINER,
				container.getScrollY()
		);
	}

	private void shiftIcons(List<Widget> items, int adjustmentX, int finalY) {
		for (int j=0; j<items.size(); j++) {
			items.get(j).setOriginalX((WIDGET_ICON_WIDTH + 2 * WIDGET_OFFSET) * j + adjustmentX);
			items.get(j).setOriginalY(finalY);
			items.get(j).revalidate();
		}
	}

	private PohStorageWidget newPohStorageWidget(Widget widget) {
		return new PohStorageWidget(widget, widget.getId(), widget.getOriginalX(), widget.getOriginalY(), widget.getOriginalWidth(), widget.getOpacity(), widget.getSpriteId(), widget.isHidden(), widget.getWidthMode(), widget.getXPositionMode());
	}

	private PohStorageSet newPohStorageSet(Widget headerWidget) {
		int textId = headerWidget.getIndex();

		// Create building blocks
		PohStorageWidget outline = newPohStorageWidget(headerWidget.getParent().getChild(textId - 3));
		PohStorageWidget arrow = newPohStorageWidget(headerWidget.getParent().getChild(textId - 2));
		PohStorageWidget footer = newPohStorageWidget(headerWidget.getParent().getChild(textId - 1));
		PohStorageWidget header = newPohStorageWidget(headerWidget);

		// Get item widgets
		int stored = 0;
		int offset = 1;
		List<PohStorageWidget> items = new ArrayList<PohStorageWidget>();
		while (headerWidget.getParent().getChild(textId+offset).getWidth() == WIDGET_ICON_WIDTH) {
			if (!headerWidget.getParent().getChild(textId+offset).isHidden()) {
				items.add(newPohStorageWidget(headerWidget.getParent().getChild(textId + offset)));
				if (headerWidget.getParent().getChild(textId + offset).getOpacity()==0)
					stored++;
			}
			offset++;
		}

		int type;
		if (stored == items.size())
			type = SET_TYPE_FULL;
		else if (stored == 0)
			type = SET_TYPE_EMPTY;
		else
			type = SET_TYPE_PARTIAL;

		return new PohStorageSet(outline, arrow, footer, header, items, headerWidget.getText(), type, items.size()<=MAX_HALF_SET);
	}

}
