package com.ian.podcaststv;
public class LibraryFilterTest {
    private static void check(boolean ok) {if(!ok)throw new AssertionError();}
    public static void main(String[] args) {
        check(LibraryFilter.matches("MacBreak Weekly","Video · Club TWiT","macbreak video"));
        check(!LibraryFilter.matches("MacBreak Weekly","Audio · Club TWiT","macbreak video"));
        check(LibraryFilter.matches("Café Épisode","Oct 23","CAFE episode"));
        check(LibraryFilter.matches("Humanity Gets in the Way","11/06/2025","humanity 2025"));
        check(LibraryFilter.matches("Anything","Audio","  "));
        check(!LibraryFilter.matches("Something else","Video","humanity"));
        System.out.println("Native filter checks passed");
    }
}
