package com.spotifyclient.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

public final class ContextMenu {
	private static final int ROW_HEIGHT = 14;
	private static final int PADDING = 3;
	private static final int BACKGROUND = 0xFF282828;
	private static final int BORDER = 0xFF3E3E3E;
	private static final int HOVER = 0xFF3E3E3E;
	private static final int DANGER = 0xFFE9565C;

	public record Entry(Component label, @Nullable Runnable action, @Nullable Supplier<List<Entry>> submenu, boolean danger) {
		public static Entry of(Component label, Runnable action) {
			return new Entry(label, action, null, false);
		}

		public static Entry danger(Component label, Runnable action) {
			return new Entry(label, action, null, true);
		}

		public static Entry submenu(Component label, Supplier<List<Entry>> entries) {
			return new Entry(label, null, entries, false);
		}

		public static Entry disabled(Component label) {
			return new Entry(label, null, null, false);
		}
	}

	private final Supplier<List<Entry>> entries;
	private final int anchorX;
	private final int anchorY;
	private final int parentLeft;
	private int x;
	private int y;
	private int width;
	private int height;
	private List<Entry> current = List.of();
	private @Nullable ContextMenu child;
	private int childIndex = -1;

	public ContextMenu(Supplier<List<Entry>> entries, int anchorX, int anchorY) {
		this(entries, anchorX, anchorY, -1);
	}

	private ContextMenu(Supplier<List<Entry>> entries, int anchorX, int anchorY, int parentLeft) {
		this.entries = entries;
		this.anchorX = anchorX;
		this.anchorY = anchorY;
		this.parentLeft = parentLeft;
	}

	public void extract(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, int screenWidth, int screenHeight) {
		layout(font, screenWidth, screenHeight);
		graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, BORDER);
		graphics.fill(x, y, x + width, y + height, BACKGROUND);

		int hoveredIndex = -1;
		for (int i = 0; i < current.size(); i++) {
			Entry entry = current.get(i);
			int rowY = y + PADDING + i * ROW_HEIGHT;
			boolean enabled = entry.action() != null || entry.submenu() != null;
			boolean hovered = enabled && Ui.inside(mouseX, mouseY, x, rowY, x + width, rowY + ROW_HEIGHT);
			if (hovered || (i == childIndex && child != null)) {
				graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, HOVER);
			}
			if (hovered) {
				hoveredIndex = i;
			}
			int color = !enabled ? Ui.DARK_GRAY : entry.danger() ? DANGER : Ui.WHITE;
			graphics.text(font, entry.label(), x + 6, rowY + 3, color, false);
			if (entry.submenu() != null) {
				graphics.text(font, "›", x + width - 8, rowY + 3, Ui.GRAY, false);
			}
		}

		if (hoveredIndex >= 0 && hoveredIndex != childIndex) {
			Entry hovered = current.get(hoveredIndex);
			childIndex = hovered.submenu() != null ? hoveredIndex : -1;
			child = hovered.submenu() != null ? new ContextMenu(hovered.submenu(), x + width, y + PADDING + hoveredIndex * ROW_HEIGHT - PADDING, x) : null;
		}
		if (child != null) {
			child.extract(graphics, font, mouseX, mouseY, screenWidth, screenHeight);
		}
	}

	private void layout(Font font, int screenWidth, int screenHeight) {
		current = entries.get();
		width = 80;
		for (Entry entry : current) {
			width = Math.max(width, font.width(entry.label()) + 22);
		}
		height = current.size() * ROW_HEIGHT + PADDING * 2;
		boolean overflowsRight = anchorX + width > screenWidth - 2;
		x = overflowsRight && parentLeft >= 0 ? parentLeft - width : Math.min(anchorX, screenWidth - width - 2);
		y = Math.min(anchorY, screenHeight - height - 2);
		x = Math.max(2, x);
		y = Math.max(2, y);
	}

	public boolean contains(double mouseX, double mouseY) {
		return Ui.inside(mouseX, mouseY, x - 1, y - 1, x + width + 1, y + height + 1) || (child != null && child.contains(mouseX, mouseY));
	}

	public boolean click(double mouseX, double mouseY) {
		if (child != null && child.contains(mouseX, mouseY)) {
			return child.click(mouseX, mouseY);
		}
		for (int i = 0; i < current.size(); i++) {
			int rowY = y + PADDING + i * ROW_HEIGHT;
			if (Ui.inside(mouseX, mouseY, x, rowY, x + width, rowY + ROW_HEIGHT)) {
				Runnable action = current.get(i).action();
				if (action != null) {
					action.run();
					return true;
				}
				return false;
			}
		}
		return false;
	}
}
