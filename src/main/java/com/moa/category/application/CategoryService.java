package com.moa.category.application;

import com.moa.category.dto.CategoryMeetingCreateDto;
import com.moa.category.dto.CategoryMeetingGetDto;
import com.moa.category.dto.MeetingDetailGetDto;
import com.moa.category.dto.UserInterestGetDto;
import com.moa.category.vo.request.UserCategoriesIn;
import com.moa.category.vo.response.CategoriesListOut;
import com.moa.category.vo.request.CreateThemeCategoryIn;
import com.moa.category.vo.response.MeetingListOut;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CategoryService {
    MeetingDetailGetDto getMeetingId(Long id);

    List<CategoriesListOut> categoriesList();

    void createUserInterests(UserInterestGetDto UserInterestGetDto);

    void createThemeCategory(CreateThemeCategoryIn createThemeCategoryIn);

    void createMeetingCategory(CategoryMeetingCreateDto categoryMeetingCreateDto);

    void disableMeetingCategory(Long meetingId);

    void updateUserInterests(UserInterestGetDto UserInterestGetDto);

    List<Integer> getUserInterests(UUID uuid);

    MeetingListOut getMeetingListByCategory(UserCategoriesIn userPreferences, int categoryId);
}
