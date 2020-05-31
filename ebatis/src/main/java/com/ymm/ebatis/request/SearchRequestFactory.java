package com.ymm.ebatis.request;

import com.ymm.ebatis.annotation.MultiSearch;
import com.ymm.ebatis.annotation.QueryType;
import com.ymm.ebatis.annotation.Search;
import com.ymm.ebatis.builder.QueryBuilderFactory;
import com.ymm.ebatis.common.DslUtils;
import com.ymm.ebatis.domain.ContextHolder;
import com.ymm.ebatis.domain.Pageable;
import com.ymm.ebatis.domain.ScriptField;
import com.ymm.ebatis.domain.Sort;
import com.ymm.ebatis.meta.MethodMeta;
import com.ymm.ebatis.meta.ParameterMeta;
import com.ymm.ebatis.provider.CollapseProvider;
import com.ymm.ebatis.provider.ScriptFieldProvider;
import com.ymm.ebatis.provider.SortProvider;
import com.ymm.ebatis.provider.SourceProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.Requests;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 章多亮
 * @since 2019/12/17 15:32
 */
@Slf4j
class SearchRequestFactory extends AbstractRequestFactory<Search, SearchRequest> {
    static final SearchRequestFactory INSTANCE = new SearchRequestFactory();
    private static final Map<MethodMeta, QueryBuilderFactory> QUERY_BUILDER_FACTORIES = new ConcurrentHashMap<>();

    private SearchRequestFactory() {
    }

    @Override
    protected void setAnnotationMeta(SearchRequest request, Search search) {
        request.routing(DslUtils.getRouting(search.routing()))
                .preference(StringUtils.trimToNull(search.preference()))
                .searchType(search.searchType());
    }

    @Override
    protected SearchRequest doCreate(MethodMeta meta, Object[] args) {
        // 目前支持一个入参作为条件查询，所以可以通过多参数变成一个实体类
        // 传过来的只有一个入参条件
        Optional<ParameterMeta> conditionMeta = meta.findConditionParameter();
        Object condition = conditionMeta.map(p -> p.getValue(args)).orElse(null);

        // 1. 如果是一个入参
        SearchRequest request = Requests.searchRequest(meta.getIndices());

        // 获取语句构建器，不能的查询语句是不一样的
        QueryBuilderFactory factory = getQueryBuilderFactory(meta);

        // 创建查询语句
        QueryBuilder queryBuilder = factory.create(conditionMeta.orElse(null), condition);

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.query(queryBuilder);

        meta.getPageableParameter()
                .map(p -> p.getValue(args))
                .map(Pageable.class::cast)
                .ifPresent(p -> {
                    ContextHolder.setPageable(p);
                    searchSource.from(p.getFrom()).size(p.getSize());
                });

        additionalProvider(condition, searchSource);

        request.source(searchSource);

        return request;
    }

    private QueryBuilderFactory getQueryBuilderFactory(MethodMeta meta) {
        return QUERY_BUILDER_FACTORIES.computeIfAbsent(meta, m -> m.findAnnotation(Search.class).map(Search::queryType)
                .orElseGet(() -> m.findAnnotation(MultiSearch.class).map(MultiSearch::queryType).orElse(QueryType.AUTO))
                .getQueryBuilderFactory());
    }

    private void additionalProvider(Object condition, SearchSourceBuilder searchSource) {
        if (condition instanceof ScriptFieldProvider && ArrayUtils.isNotEmpty(((ScriptFieldProvider) condition).getScriptFields())) {
            ScriptField[] fields = ((ScriptFieldProvider) condition).getScriptFields();
            for (ScriptField field : fields) {
                searchSource.scriptField(field.getName(), field.getScript().toEsScript());
            }
        }

        if (condition instanceof SortProvider && ArrayUtils.isNotEmpty(((SortProvider) condition).getSorts())) {
            Sort[] sorts = ((SortProvider) condition).getSorts();
            for (Sort sort : sorts) {
                searchSource.sort(sort.toSortBuilder());
            }
        }

        if (condition instanceof SourceProvider) {
            SourceProvider sourceProvider = (SourceProvider) condition;
            searchSource.fetchSource(sourceProvider.getIncludeFields(), sourceProvider.getExcludeFields());
        }

        if (condition instanceof CollapseProvider && Objects.nonNull(((CollapseProvider) condition).getCollapse())) {
            CollapseProvider collapseProvider = (CollapseProvider) condition;
            searchSource.collapse(collapseProvider.getCollapse().toCollapseBuilder());
        }
    }
}