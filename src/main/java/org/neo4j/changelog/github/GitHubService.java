package org.neo4j.changelog.github;

import okhttp3.*;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.util.List;

public interface GitHubService {
    String API_URL = "https://api.github.com";


    @GET("/repos/{user}/{repo}/pulls?state=closed&per_page=100")
    Call<List<PR>> listPRs(@Path("user") String user, @Path("repo") String repo, @Query("page") int page);


    static GitHubService GetService(@Nonnull String token) {
        return GetService(API_URL, token);
    }

    static GitHubService GetService(@Nonnull String url, @Nonnull String token) {
        return GetService(url, token, null);
    }

    static GitHubService GetService(@Nonnull String url, @Nonnull String token, @Nullable final Interceptor interceptor) {

        if (!url.endsWith("/")) {
            url += "/";
        }

        File cacheDir = new File(".okhttpcache");
        if (!cacheDir.isDirectory() && !cacheDir.mkdir()) {
            throw new RuntimeException("Failed to create cache directory");
        }
        Cache cache = new Cache(cacheDir, 1024 * 1024 * 10);

        OkHttpClient.Builder httpBuilder = new OkHttpClient.Builder().cache(cache);

        if (!token.isEmpty()) {
            httpBuilder.addInterceptor(chain -> {
                Request original = chain.request();

                Request request = original.newBuilder()
                        .addHeader("Authorization", "token " + token)
                        .method(original.method(), original.body())
                        .build();
                return chain.proceed(request);
            });
        }

        if (interceptor != null) {
            httpBuilder.addInterceptor(interceptor);
        }


        Retrofit retrofit = new Retrofit.Builder().baseUrl(url).addConverterFactory(GsonConverterFactory.create())
                .client(httpBuilder.build()).build();

        return retrofit.create(GitHubService.class);
    }

    class PR {
        public int number;
        public String title;
        public String body;
        public String html_url;
        public String merged_at;
        public String merge_commit_sha;
        public Base base;

        public boolean isMerged() {
            return merged_at != null;
        }

        public static class Base {
            public String ref;
            public String sha;
        }
    }
}
