/*
 * Copyright 2006 National Institute of Advanced Industrial Science
 * and Technology (AIST), and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ow.tool.util.vizframework;

import java.awt.Graphics;
import java.awt.Image;
import java.awt.Point;

import javax.swing.JComponent;

import ow.id.ID;

final class MarkPanel extends JComponent {
	private final ImageManager imageManager;
	private final ID id;
	private final int type;

	private Point location;

	MarkPanel(ImageManager imageManager, ID id, int type) {
		this.imageManager = imageManager;
		this.id = id;
		this.type = type;

		this.resetSize();

		// set Tool Tips text
		this.setToolTipText(id.toString());
	}

	/**
	 * Adjust the size according to ImageManager.
	 */
	protected void resetSize() {
		this.setSize(this.imageManager.getMarkWidth(this.type), this.imageManager.getMarkHeight(this.type));
		this.resetLocation();
	}

	protected void resetLocation() {
		this.location = this.imageManager.getMarkLocation(this.id, this.type);
		this.setLocation(this.location);
	}

	protected void paintComponent(Graphics g) {
		if (!Visualizer.SELF_DRAWING) {
			Image img = this.imageManager.getMarkImage(this.type);
			g.drawImage(img, 0, 0, this);
		}
	}

	protected void drawComponent(Graphics g) {
		Image img = this.imageManager.getMarkImage(this.type);
		g.drawImage(img, this.location.x, this.location.y, this);
	}
}
