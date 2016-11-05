package me.qyh.blog.service.impl;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.document.DateTools.Resolution;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TrackingIndexWriter;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery.Builder;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortField.Type;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;
import org.lionsoul.jcseg.analyzer.v5x.JcsegAnalyzer5X;
import org.lionsoul.jcseg.tokenizer.core.ADictionary;
import org.lionsoul.jcseg.tokenizer.core.ILexicon;
import org.lionsoul.jcseg.tokenizer.core.IWord;
import org.lionsoul.jcseg.tokenizer.core.JcsegTaskConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.util.CollectionUtils;

import me.qyh.blog.dao.TagDao;
import me.qyh.blog.entity.Article;
import me.qyh.blog.entity.Article.ArticleFrom;
import me.qyh.blog.entity.Article.ArticleStatus;
import me.qyh.blog.entity.Space;
import me.qyh.blog.entity.Tag;
import me.qyh.blog.exception.SystemException;
import me.qyh.blog.pageparam.ArticleQueryParam;
import me.qyh.blog.pageparam.PageResult;
import me.qyh.util.Validators;

public class NrtArticleIndexer implements ArticleIndexer, InitializingBean, ApplicationListener<ContextClosedEvent> {

	@Autowired
	private TagDao tagDao;

	private static final Logger logger = LoggerFactory.getLogger(NrtArticleIndexer.class);

	private final Analyzer analyzer;
	private final ControlledRealTimeReopenThread<IndexSearcher> reopenThread;
	private final TrackingIndexWriter writer;
	private final ReferenceManager<IndexSearcher> searcherManager;
	private final Directory dir;
	private final IndexWriter oriWriter;

	private JcsegAnalyzer5X analyzer5x;

	/**
	 * 最大查询数量
	 */
	private static final int MAX_RESULTS = 1000;
	/**
	 * 每个字段 索引term个数限制
	 */
	private static final int MAX_CONTENT_TERM_RESULTS = 100;

	private int maxContentTermResults = MAX_CONTENT_TERM_RESULTS;

	private static final long DEFAULT_COMMIT_PERIOD = 30 * 60 * 1000;

	private Set<String> tags = new HashSet<String>();

	public enum JcsegMode {

		SIMPLE(1), COMPLEX(2), DECECT(3), SEARCH(4);

		private int mode;

		private JcsegMode(int mode) {
			this.mode = mode;
		}

		public int getMode() {
			return mode;
		}
	}

	public NrtArticleIndexer(String indexDir, JcsegMode mode, long commitPeriod) throws IOException {
		this.dir = FSDirectory.open(Paths.get(indexDir));
		JcsegTaskConfig taskConfig = new JcsegTaskConfig();
		taskConfig.setClearStopwords(true);
		analyzer5x = new JcsegAnalyzer5X(mode.getMode(), taskConfig);
		this.analyzer = new LimitTokenCountAnalyzer(analyzer5x, maxContentTermResults);
		IndexWriterConfig config = new IndexWriterConfig(analyzer);
		config.setOpenMode(OpenMode.CREATE_OR_APPEND);
		try {
			oriWriter = new IndexWriter(dir, config);
			oriWriter.commit();
		} catch (IOException e) {
			throw new SystemException(e.getMessage(), e);
		}
		if (commitPeriod <= 0) {
			commitPeriod = DEFAULT_COMMIT_PERIOD;
		}
		new ScheduledThreadPoolExecutor(1, new ThreadFactory() {

			@Override
			public Thread newThread(Runnable r) {
				Thread thread = new Thread();
				thread.setDaemon(true);
				return thread;
			}
		}).scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				try {
					oriWriter.commit();
				} catch (IOException e) {
					logger.error(e.getMessage(), e);
				}
			}
		}, commitPeriod, commitPeriod, TimeUnit.MILLISECONDS);

		writer = new TrackingIndexWriter(oriWriter);
		searcherManager = new SearcherManager(writer.getIndexWriter(), new SearcherFactory());
		reopenThread = new ControlledRealTimeReopenThread<>(writer, searcherManager, 0.5, 0.01);
		reopenThread.setName("Article_Index_NRT");
		reopenThread.setPriority(Math.min(Thread.currentThread().getPriority() + 2, Thread.MAX_PRIORITY));
		reopenThread.setDaemon(true);
		reopenThread.start();
	}

	public void setJcsegTaskConfig(JcsegTaskConfig jcsegTaskConfig) {
		analyzer5x.setConfig(jcsegTaskConfig);
	}

	@Override
	public void onApplicationEvent(ContextClosedEvent event) {
		close();
	}

	public void close() {
		try {
			reopenThread.close();
			searcherManager.maybeRefreshBlocking();
			writer.getIndexWriter().commit();
			writer.getIndexWriter().close();
			dir.close();
		} catch (AlreadyClosedException e) {
		} catch (IOException e) {
			logger.warn(e.getMessage(), e);
		}
	}

	private static final String ID = "id";
	private static final String TITLE = "title";
	private static final String CONTENT = "content";
	private static final String SPACE_ID = "spaceId";
	private static final String PRIVATE = "private";
	private static final String STATUS = "status";
	private static final String FROM = "from";
	private static final String LEVEL = "level";
	private static final String HITS = "hits";
	private static final String COMMENTS = "comments";
	private static final String PUB_DATE = "pubDate";
	private static final String TAG = "tag";
	private static final String LOCKED = "locked";
	private static final String ALIAS = "alias";
	private static final String SPACE_PRIVATE = "spacePrivate";

	protected Document buildDocument(Article article) {
		Document doc = new Document();
		doc.add(new StringField(ID, article.getId().toString(), Field.Store.YES));
		doc.add(new TextField(TITLE, article.getTitle(), Field.Store.NO));
		doc.add(new TextField(CONTENT, clean(article.getContent()), Field.Store.NO));
		doc.add(new StringField(SPACE_ID, article.getSpace().getId().toString(), Field.Store.NO));
		doc.add(new StringField(PRIVATE, article.isPrivate().toString(), Field.Store.NO));
		doc.add(new StringField(STATUS, article.getStatus().name().toLowerCase(), Field.Store.NO));
		doc.add(new StringField(FROM, article.getFrom().name().toLowerCase(), Field.Store.NO));
		doc.add(new StringField(LOCKED, article.hasLock() ? "true" : "false", Field.Store.NO));
		if (article.getAlias() != null) {
			doc.add(new TextField(ALIAS, article.getAlias(), Field.Store.NO));
		}
		Integer level = article.getLevel();
		doc.add(new NumericDocValuesField(LEVEL, (level == null ? -1 : level)));
		doc.add(new NumericDocValuesField(HITS, article.getHits()));
		doc.add(new NumericDocValuesField(COMMENTS, article.getComments()));
		String pubDateStr = timeToString(article.getPubDate());
		BytesRef pubDate = new BytesRef(pubDateStr);
		doc.add(new SortedDocValuesField(PUB_DATE, pubDate));
		doc.add(new StringField(PUB_DATE, pubDateStr, Field.Store.NO));
		doc.add(new SortedDocValuesField(ID, new BytesRef(article.getId().toString())));
		Set<Tag> tags = article.getTags();
		if (!CollectionUtils.isEmpty(tags)) {
			for (Tag tag : tags) {
				doc.add(new StringField(TAG, tag.getName().toLowerCase(), Field.Store.YES));
			}
		}
		doc.add(new StringField(SPACE_PRIVATE, article.getSpacePrivate().toString(), Field.Store.NO));
		return doc;
	}

	protected String timeToString(Date date) {
		return DateTools.timeToString(date.getTime(), Resolution.MILLISECOND);
	}

	@Override
	public void addOrUpdateDocument(Article article) {
		try {
			if (article.hasId()) {
				deleteDocument(article.getId());
			}
			writer.addDocument(buildDocument(article));
		} catch (IOException e) {
			throw new SystemException(e.getMessage(), e);
		}
	}

	@Override
	public void deleteDocument(Integer id) {
		Term term = new Term(ID, id.toString());
		try {
			writer.deleteDocuments(term);
		} catch (IOException e) {
			throw new SystemException(e.getMessage(), e);
		}
	}

	public PageResult<Integer> query(ArticleQueryParam param) {
		IndexSearcher searcher = null;
		try {
			searcherManager.maybeRefresh();
			searcher = searcherManager.acquire();

			List<SortField> fields = new ArrayList<SortField>();
			if (!param.isIgnoreLevel()) {
				fields.add(new SortField(LEVEL, Type.INT, true));
			}
			if (param.getSort() != null) {
				switch (param.getSort()) {
				case COMMENTS:
					fields.add(new SortField(COMMENTS, Type.INT, true));
					break;
				case HITS:
					fields.add(new SortField(HITS, Type.INT, true));
					break;
				}
			}
			fields.add(new SortField(PUB_DATE, SortField.Type.STRING, true));
			fields.add(new SortField(ID, SortField.Type.STRING, true));
			logger.debug(fields.toString());
			Sort sort = new Sort(fields.toArray(new SortField[] {}));
			Query query = parseBlogPageParam(param);
			logger.debug(query.toString());

			TopDocs tds = searcher.search(query, MAX_RESULTS, sort);
			int total = tds.totalHits;
			int offset = param.getOffset();
			List<Integer> datas = new ArrayList<Integer>();
			if (offset < total) {
				ScoreDoc[] docs = tds.scoreDocs;
				int last = offset + param.getPageSize();
				for (int i = offset; i < Math.min(Math.min(last, total), MAX_RESULTS); i++) {
					Document doc = searcher.doc(docs[i].doc);
					datas.add(Integer.parseInt(doc.get(ID)));
				}
			}
			return new PageResult<Integer>(param, Math.min(MAX_RESULTS, total), datas);
		} catch (IOException e) {
			throw new SystemException(e.getMessage(), e);
		} finally {
			try {
				searcherManager.release(searcher);
			} catch (Exception e) {
				//
			}
		}
	}

	protected Query parseBlogPageParam(ArticleQueryParam param) {
		Builder builder = new Builder();
		Space space = param.getSpace();
		if (space != null) {
			Query query = new TermQuery(new Term(SPACE_ID, space.getId().toString()));
			builder.add(query, Occur.MUST);
		}
		Date begin = param.getBegin();
		Date end = param.getEnd();
		boolean dateRangeQuery = (begin != null && end != null);
		if (dateRangeQuery) {
			TermRangeQuery query = new TermRangeQuery(PUB_DATE, new Term(PUB_DATE, timeToString(begin)).bytes(),
					new Term(PUB_DATE, timeToString(end)).bytes(), true, true);
			builder.add(query, Occur.MUST);
		}
		if (!param.isQueryPrivate()) {
			builder.add(new TermQuery(new Term(PRIVATE, "false")), Occur.MUST);
			builder.add(new TermQuery(new Term(LOCKED, "false")), Occur.MUST);
		}
		if (!param.isQuerySpacePrivate()) {
			builder.add(new TermQuery(new Term(SPACE_PRIVATE, "false")), Occur.MUST);
		}
		ArticleFrom from = param.getFrom();
		if (from != null) {
			Query query = new TermQuery(new Term(FROM, from.name().toLowerCase()));
			builder.add(query, Occur.MUST);
		}
		ArticleStatus status = param.getStatus();
		if (status != null) {
			Query query = new TermQuery(new Term(STATUS, status.name().toLowerCase()));
			builder.add(query, Occur.MUST);
		} else {
			List<ArticleStatus> statuses = param.getStatuses();
			if (!CollectionUtils.isEmpty(statuses)) {
				for (ArticleStatus _status : statuses) {
					Query query = new TermQuery(new Term(STATUS, _status.name().toLowerCase()));
					builder.add(query, Occur.SHOULD);
				}
			}
		}
		if (param.getTag() != null) {
			builder.add(new TermQuery(new Term(TAG, param.getTag())), Occur.MUST);
		}
		if (!Validators.isEmptyOrNull(param.getQuery(), true)) {
			MultiFieldQueryParser parser = new MultiFieldQueryParser(new String[] { TAG, TITLE, ALIAS, CONTENT },
					analyzer);
			try {
				builder.add(parser.parse(param.getQuery()), Occur.MUST);
			} catch (ParseException e) {
				// ingore
			}
		}
		return builder.build();
	}

	@Override
	public void deleteAll() {
		try {
			oriWriter.deleteAll();
		} catch (IOException e) {
			throw new SystemException(e.getMessage(), e);
		}
	}

	private String clean(String content) {
		return Jsoup.clean(content, Whitelist.none());
	}

	public void setMaxContentTermResults(int maxContentTermResults) {
		this.maxContentTermResults = maxContentTermResults;
	}

	@Override
	public synchronized void removeTag(String... tags) {
		ADictionary dict = analyzer5x.getDict();
		for (String tag : tags) {
			this.tags.remove(tag);
			dict.remove(ILexicon.CJK_WORD, tag);
		}
	}

	@Override
	public synchronized void reloadTags() {
		ADictionary dict = analyzer5x.getDict();
		for (String tag : this.tags) {
			dict.remove(ILexicon.CJK_WORD, tag);
		}
		this.tags.clear();
		// 将所有tag加载到字典中
		List<Tag> tags = tagDao.selectAll();
		if (!CollectionUtils.isEmpty(tags)) {
			List<String> _tags = new ArrayList<>();
			for (Tag tag : tags) {
				_tags.add(tag.getName());
			}
			addTags(_tags.toArray(new String[] {}));
		}
	}

	@Override
	public void addTags(String... tags) {
		synchronized (this) {
			ADictionary dict = analyzer5x.getDict();
			for (String tag : tags) {
				this.tags.add(tag);
				dict.add(ILexicon.CJK_WORD, tag, IWord.T_CJK_WORD);
			}
		}
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		reloadTags();
	}
}
