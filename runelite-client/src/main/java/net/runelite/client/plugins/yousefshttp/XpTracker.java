package net.runelite.client.plugins.yousefshttp;
import net.runelite.api.Skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class XpTracker {

	private Map<Skill, ArrayList> skillXpMap = new HashMap<Skill, ArrayList>();

	private HttpServerPlugin httpPlugin;

	public XpTracker(HttpServerPlugin httpPlugin) {

		this.httpPlugin = httpPlugin;

		for (int i = 0; i < httpPlugin.skillList.length; i++) {
			ArrayList<Integer> newXpList = new ArrayList<Integer>();
			skillXpMap.put(httpPlugin.skillList[i], newXpList);
		}

	}

	public void update() {
		for (int i = 0; i < httpPlugin.skillList.length; i++) {
			Skill skillToUpdate = httpPlugin.skillList[i];
			ArrayList<Integer> xpListToUpdate = skillXpMap.get(skillToUpdate);
			int xpValueToAdd = httpPlugin.getClient().getSkillExperience(skillToUpdate);
			xpListToUpdate.add(xpValueToAdd);
		}
	}

	public int getXpData(Skill skillToGet, int tickNum) {
		ArrayList<Integer> xpListToGet = skillXpMap.get(skillToGet);
		if (xpListToGet == null || xpListToGet.isEmpty()) {
			return 0;
		}
		// If tickNum is out of bounds, return the most recent value
		if (tickNum >= xpListToGet.size()) {
			return xpListToGet.get(xpListToGet.size() - 1);
		}
		if (tickNum < 0) {
			return xpListToGet.get(0);
		}
		return xpListToGet.get(tickNum);
	}

	public int getMostRecentXp(Skill skillToGet) {
		ArrayList<Integer> xpListToGet = skillXpMap.get(skillToGet);
		return xpListToGet.get(xpListToGet.size()-1);
	}

}
