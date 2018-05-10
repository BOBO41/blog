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
package me.qyh.blog.core.service.impl;

import me.qyh.blog.core.entity.Article;

/**
 * 文章缓存点击策略
 * <p>
 * 文章可能存在于缓存中，需要在合适的时机手动更新缓存和文章索引
 * </p>
 * 
 * @see ArticleIndexer#addOrUpdateDocument(Integer...)
 * 
 * @see DefaultHitsStrategy
 * @author mhlx
 *
 */
public interface HitsStrategy {
	/**
	 * 点击文章
	 * 
	 * @param article
	 */
	void hit(Article article);
}