package io.manbang.ebatis.core.response;

import org.elasticsearch.index.reindex.BulkByScrollResponse;

/**
 * @author duoliang.zhang
 */
public interface DeleteByQueryResponseExtractor<T> extends ConcreteResponseExtractor<T, BulkByScrollResponse> {
}
