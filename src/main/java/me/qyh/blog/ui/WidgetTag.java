package me.qyh.blog.ui;

import java.util.HashMap;
import java.util.Map;

public class WidgetTag {

	// 挂件名
	private String name;
	private Map<String, String> attrs = new HashMap<String, String>();

	public String getName() {
		return name;
	}

	public WidgetTag(String name) {
		this.name = name;
	}

	public Map<String, String> getAttrs() {
		return attrs;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void put(String key, String v) {
		attrs.put(key, v);
	}

	public String getAttr(String key) {
		return attrs.get(key);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		WidgetTag other = (WidgetTag) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		return true;
	}

}
