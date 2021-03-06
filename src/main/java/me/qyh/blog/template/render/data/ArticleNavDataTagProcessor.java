/*
 * Copyright 2016 qyh.me
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
package me.qyh.blog.template.render.data;

import org.springframework.beans.factory.annotation.Autowired;

import me.qyh.blog.core.entity.Article;
import me.qyh.blog.core.exception.LogicException;
import me.qyh.blog.core.service.ArticleService;
import me.qyh.blog.core.vo.ArticleNav;

public class ArticleNavDataTagProcessor extends DataTagProcessor<ArticleNav> {

	private static final String ID_OR_ALIAS = "idOrAlias";
	private static final String REF_ARTICLE = "article";

	@Autowired
	private ArticleService articleService;

	public ArticleNavDataTagProcessor(String name, String dataName) {
		super(name, dataName);
	}

	@Override
	protected ArticleNav query(Attributes attributes) throws LogicException {
		boolean queryLock = attributes.getBoolean("queryLock").orElse(false);
		Object refArt = attributes.get(REF_ARTICLE).orElse(null);
		if (refArt != null) {
			return articleService.getArticleNav((Article) refArt, queryLock).orElse(null);
		}
		return attributes.getString(ID_OR_ALIAS)
				.flatMap(idOrAlias -> articleService.getArticleNav(idOrAlias, queryLock)).orElse(null);
	}

}
