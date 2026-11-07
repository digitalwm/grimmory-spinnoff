import {ChangeDetectorRef, DestroyRef, signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {FormControl, FormGroup} from '@angular/forms';
import {ActivatedRoute} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {ConfirmationService, MessageService} from '@openng/optimus-ui/api';
import {afterEach, expect, it, vi} from 'vitest';
import {BookdropFileReviewComponent, BookdropFileUI} from './bookdrop-file-review.component';
import {BookdropService, BookdropFileStatus} from '../../service/bookdrop.service';
import {BookdropFileService} from '../../service/bookdrop-file.service';
import {LibraryService} from '../../../book/service/library.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {DialogLauncherService} from '../../../../shared/services/dialog-launcher.service';
import {PageTitleService} from '../../../../shared/service/page-title.service';
import {UrlHelperService} from '../../../../shared/service/url-helper.service';
import {MetadataProviderFieldsService} from '../../../../shared/metadata';

afterEach(() => TestBed.resetTestingModule());
it('bulk copy skips a rejected match even if stale fetched data is still present in the client', () => {
  TestBed.configureTestingModule({providers:[
    {provide:ChangeDetectorRef,useValue:{markForCheck:vi.fn()}},
    {provide:DestroyRef,useValue:{onDestroy:vi.fn()}},
    {provide:BookdropService,useValue:{}},{provide:BookdropFileService,useValue:{}},
    {provide:LibraryService,useValue:{libraries:signal([])}},
    {provide:AppSettingsService,useValue:{appSettings:signal(null)}},
    {provide:DialogLauncherService,useValue:{}},{provide:PageTitleService,useValue:{}},
    {provide:UrlHelperService,useValue:{}},{provide:MetadataProviderFieldsService,useValue:{fields:signal([])}},
    {provide:ActivatedRoute,useValue:{}},{provide:ConfirmationService,useValue:{}},{provide:MessageService,useValue:{}},
    {provide:TranslocoService,useValue:{translate:(key:string)=>key}},
  ]});
  const component=TestBed.runInInjectionContext(()=>new BookdropFileReviewComponent());
  const row=(id:number,rejected:boolean):BookdropFileUI=>({
    file:{id,fileName:'Elle Scott - Ever Fallen.epub',filePath:'/bookdrop/book.epub',fileSize:1024,showDetails:false,
      createdAt:'now',updatedAt:'now',status:BookdropFileStatus.ACCEPTED,metadataMatchRejected:rejected,
      fetchedMetadata:{bookId:id,title:rejected?'Never Have I Ever Fallen for a Movie Star':'Ever Fallen',description:'Fetched description'}},
    metadataForm:new FormGroup({title:new FormControl('Ever Fallen'),description:new FormControl('Embedded description')}),
    selected:true,showDetails:false,copiedFields:{},savedFields:{},selectedLibraryId:null,selectedPathId:null,availablePaths:[],
  });
  const rejected=row(1,true),accepted=row(2,false);component.fileUiCache={1:rejected};expect(component.hasCopyableMetadata).toBe(false);
  component.fileUiCache={1:rejected,2:accepted};expect(component.hasCopyableMetadata).toBe(true);
  component.copyMetadata();
  expect(rejected.metadataForm.get('title')?.value).toBe('Ever Fallen');
  expect(rejected.metadataForm.get('description')?.value).toBe('Embedded description');
  expect(component.copiedFlags[1]).toBeUndefined();
  expect(accepted.metadataForm.get('description')?.value).toBe('Fetched description');
  expect(component.copiedFlags[2]).toBe(true);
});
