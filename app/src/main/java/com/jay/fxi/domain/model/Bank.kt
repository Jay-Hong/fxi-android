package com.jay.fxi.domain.model

import com.jay.fxi.R

/**
 * 은행
 * colorHex는 UI 레이어에서 Color로 변환하여 사용
 * iconRes는 은행 로고 이미지 리소스 ID (65x65, 메인 환율 리스트용)
 * iconResLarge는 고해상도 은행 로고 (200x200, 알림 추가/수정용)
 */
enum class Bank(
    val code: String,
    val displayName: String,
    val shortName: String,
    val colorHex: Long,
    val iconRes: Int,
    val iconResLarge: Int
) {
    INVESTING("investing", "인베스팅", "인베스팅", 0xFF2C3E50, R.drawable.ic_bank_investing, R.drawable.ic_bank_investing_large),
    KB("kb", "국민은행", "국민", 0xFFFFB200, R.drawable.ic_bank_kb, R.drawable.ic_bank_kb_large),
    HANA("hana", "하나은행", "하나", 0xFF009792, R.drawable.ic_bank_hana, R.drawable.ic_bank_hana_large),
    SHINHAN("shinhan", "신한은행", "신한", 0xFF0052FF, R.drawable.ic_bank_shinhan, R.drawable.ic_bank_shinhan_large),
    WOORI("woori", "우리은행", "우리", 0xFF0089D4, R.drawable.ic_bank_woori, R.drawable.ic_bank_woori_large),
    IBK("ibk", "기업은행", "기업", 0xFF0049A0, R.drawable.ic_bank_ibk, R.drawable.ic_bank_ibk_large),
    NH("nh", "농협은행", "농협", 0xFF00AC41, R.drawable.ic_bank_nh, R.drawable.ic_bank_nh_large),
    SC("sc", "SC제일", "SC", 0xFF0075F2, R.drawable.ic_bank_sc, R.drawable.ic_bank_sc_large),
    BS("bs", "부산은행", "부산", 0xFFDD1A25, R.drawable.ic_bank_bs, R.drawable.ic_bank_bs_large),
    CITI("citi", "씨티은행", "씨티", 0xFF006EB3, R.drawable.ic_bank_citi, R.drawable.ic_bank_citi_large);

    val isReference: Boolean get() = this == INVESTING

    companion object {
        fun fromCode(code: String): Bank? = entries.find { it.code == code }

        /** 표시 순서대로 정렬된 은행 목록 */
        val sortedEntries: List<Bank> = entries.toList()
    }
}
