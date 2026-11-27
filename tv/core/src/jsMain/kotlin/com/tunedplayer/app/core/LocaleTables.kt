package com.tunedplayer.app.core

/**
 * The tables the JVM's `Locale` gives :core on Android (ISO countries and languages with their English names),
 * written out for JavaScript, which has no such list on a 2020 TV's browser (`Intl.DisplayNames` came later).
 * Generated from the JDK; a country or language added there later only needs a line here.
 */
internal object LocaleTables {
    /** `CODE=English name|`, one per ISO 3166 country. */
    const val COUNTRIES =
        "AD=Andorra|AE=United Arab Emirates|AF=Afghanistan|AG=Antigua & Barbuda|AI=Anguilla|AL=Albania|" +
            "AM=Armenia|AO=Angola|AQ=Antarctica|AR=Argentina|AS=American Samoa|AT=Austria|" +
            "AU=Australia|AW=Aruba|AX=Åland Islands|AZ=Azerbaijan|BA=Bosnia & Herzegovina|BB=Barbados|" +
            "BD=Bangladesh|BE=Belgium|BF=Burkina Faso|BG=Bulgaria|BH=Bahrain|BI=Burundi|" +
            "BJ=Benin|BL=St. Barthélemy|BM=Bermuda|BN=Brunei|BO=Bolivia|BQ=Caribbean Netherlands|" +
            "BR=Brazil|BS=Bahamas|BT=Bhutan|BV=Bouvet Island|BW=Botswana|BY=Belarus|" +
            "BZ=Belize|CA=Canada|CC=Cocos (Keeling) Islands|CD=Congo - Kinshasa|CF=Central African Republic|CG=Congo - Brazzaville|" +
            "CH=Switzerland|CI=Côte d’Ivoire|CK=Cook Islands|CL=Chile|CM=Cameroon|CN=China|" +
            "CO=Colombia|CR=Costa Rica|CU=Cuba|CV=Cape Verde|CW=Curaçao|CX=Christmas Island|" +
            "CY=Cyprus|CZ=Czechia|DE=Germany|DJ=Djibouti|DK=Denmark|DM=Dominica|" +
            "DO=Dominican Republic|DZ=Algeria|EC=Ecuador|EE=Estonia|EG=Egypt|EH=Western Sahara|" +
            "ER=Eritrea|ES=Spain|ET=Ethiopia|FI=Finland|FJ=Fiji|FK=Falkland Islands|" +
            "FM=Micronesia|FO=Faroe Islands|FR=France|GA=Gabon|GB=United Kingdom|GD=Grenada|" +
            "GE=Georgia|GF=French Guiana|GG=Guernsey|GH=Ghana|GI=Gibraltar|GL=Greenland|" +
            "GM=Gambia|GN=Guinea|GP=Guadeloupe|GQ=Equatorial Guinea|GR=Greece|GS=South Georgia & South Sandwich Islands|" +
            "GT=Guatemala|GU=Guam|GW=Guinea-Bissau|GY=Guyana|HK=Hong Kong SAR China|HM=Heard & McDonald Islands|" +
            "HN=Honduras|HR=Croatia|HT=Haiti|HU=Hungary|ID=Indonesia|IE=Ireland|" +
            "IL=Israel|IM=Isle of Man|IN=India|IO=British Indian Ocean Territory|IQ=Iraq|IR=Iran|" +
            "IS=Iceland|IT=Italy|JE=Jersey|JM=Jamaica|JO=Jordan|JP=Japan|" +
            "KE=Kenya|KG=Kyrgyzstan|KH=Cambodia|KI=Kiribati|KM=Comoros|KN=St. Kitts & Nevis|" +
            "KP=North Korea|KR=South Korea|KW=Kuwait|KY=Cayman Islands|KZ=Kazakhstan|LA=Laos|" +
            "LB=Lebanon|LC=St. Lucia|LI=Liechtenstein|LK=Sri Lanka|LR=Liberia|LS=Lesotho|" +
            "LT=Lithuania|LU=Luxembourg|LV=Latvia|LY=Libya|MA=Morocco|MC=Monaco|" +
            "MD=Moldova|ME=Montenegro|MF=St. Martin|MG=Madagascar|MH=Marshall Islands|MK=North Macedonia|" +
            "ML=Mali|MM=Myanmar (Burma)|MN=Mongolia|MO=Macao SAR China|MP=Northern Mariana Islands|MQ=Martinique|" +
            "MR=Mauritania|MS=Montserrat|MT=Malta|MU=Mauritius|MV=Maldives|MW=Malawi|" +
            "MX=Mexico|MY=Malaysia|MZ=Mozambique|NA=Namibia|NC=New Caledonia|NE=Niger|" +
            "NF=Norfolk Island|NG=Nigeria|NI=Nicaragua|NL=Netherlands|NO=Norway|NP=Nepal|" +
            "NR=Nauru|NU=Niue|NZ=New Zealand|OM=Oman|PA=Panama|PE=Peru|" +
            "PF=French Polynesia|PG=Papua New Guinea|PH=Philippines|PK=Pakistan|PL=Poland|PM=St. Pierre & Miquelon|" +
            "PN=Pitcairn Islands|PR=Puerto Rico|PS=Palestinian Territories|PT=Portugal|PW=Palau|PY=Paraguay|" +
            "QA=Qatar|RE=Réunion|RO=Romania|RS=Serbia|RU=Russia|RW=Rwanda|" +
            "SA=Saudi Arabia|SB=Solomon Islands|SC=Seychelles|SD=Sudan|SE=Sweden|SG=Singapore|" +
            "SH=St. Helena|SI=Slovenia|SJ=Svalbard & Jan Mayen|SK=Slovakia|SL=Sierra Leone|SM=San Marino|" +
            "SN=Senegal|SO=Somalia|SR=Suriname|SS=South Sudan|ST=São Tomé & Príncipe|SV=El Salvador|" +
            "SX=Sint Maarten|SY=Syria|SZ=Eswatini|TC=Turks & Caicos Islands|TD=Chad|TF=French Southern Territories|" +
            "TG=Togo|TH=Thailand|TJ=Tajikistan|TK=Tokelau|TL=Timor-Leste|TM=Turkmenistan|" +
            "TN=Tunisia|TO=Tonga|TR=Türkiye|TT=Trinidad & Tobago|TV=Tuvalu|TW=Taiwan|" +
            "TZ=Tanzania|UA=Ukraine|UG=Uganda|UM=U.S. Outlying Islands|US=United States|UY=Uruguay|" +
            "UZ=Uzbekistan|VA=Vatican City|VC=St. Vincent & Grenadines|VE=Venezuela|VG=British Virgin Islands|VI=U.S. Virgin Islands|" +
            "VN=Vietnam|VU=Vanuatu|WF=Wallis & Futuna|WS=Samoa|YE=Yemen|YT=Mayotte|" +
            "ZA=South Africa|ZM=Zambia|ZW=Zimbabwe|"

    /** `code=English name=iso3|`, one per ISO 639 language. */
    const val LANGUAGES =
        "aa=Afar=aar|ab=Abkhazian=abk|ae=Avestan=ave|af=Afrikaans=afr|ak=Akan=aka|am=Amharic=amh|" +
            "an=Aragonese=arg|ar=Arabic=ara|as=Assamese=asm|av=Avaric=ava|ay=Aymara=aym|az=Azerbaijani=aze|" +
            "ba=Bashkir=bak|be=Belarusian=bel|bg=Bulgarian=bul|bh=Bihari=bih|bi=Bislama=bis|bm=Bambara=bam|" +
            "bn=Bangla=ben|bo=Tibetan=bod|br=Breton=bre|bs=Bosnian=bos|ca=Catalan=cat|ce=Chechen=che|" +
            "ch=Chamorro=cha|co=Corsican=cos|cr=Cree=cre|cs=Czech=ces|cu=Church Slavic=chu|cv=Chuvash=chv|" +
            "cy=Welsh=cym|da=Danish=dan|de=German=deu|dv=Divehi=div|dz=Dzongkha=dzo|ee=Ewe=ewe|" +
            "el=Greek=ell|en=English=eng|eo=Esperanto=epo|es=Spanish=spa|et=Estonian=est|eu=Basque=eus|" +
            "fa=Persian=fas|ff=Fula=ful|fi=Finnish=fin|fj=Fijian=fij|fo=Faroese=fao|fr=French=fra|" +
            "fy=Western Frisian=fry|ga=Irish=gle|gd=Scottish Gaelic=gla|gl=Galician=glg|gn=Guarani=grn|gu=Gujarati=guj|" +
            "gv=Manx=glv|ha=Hausa=hau|he=Hebrew=heb|hi=Hindi=hin|ho=Hiri Motu=hmo|hr=Croatian=hrv|" +
            "ht=Haitian Creole=hat|hu=Hungarian=hun|hy=Armenian=hye|hz=Herero=her|ia=Interlingua=ina|id=Indonesian=ind|" +
            "ie=Interlingue=ile|ig=Igbo=ibo|ii=Sichuan Yi=iii|ik=Inupiaq=ipk|in=Indonesian=ind|io=Ido=ido|" +
            "is=Icelandic=isl|it=Italian=ita|iu=Inuktitut=iku|iw=Hebrew=heb|ja=Japanese=jpn|ji=Yiddish=yid|" +
            "jv=Javanese=jav|ka=Georgian=kat|kg=Kongo=kon|ki=Kikuyu=kik|kj=Kuanyama=kua|kk=Kazakh=kaz|" +
            "kl=Kalaallisut=kal|km=Khmer=khm|kn=Kannada=kan|ko=Korean=kor|kr=Kanuri=kau|ks=Kashmiri=kas|" +
            "ku=Kurdish=kur|kv=Komi=kom|kw=Cornish=cor|ky=Kyrgyz=kir|la=Latin=lat|lb=Luxembourgish=ltz|" +
            "lg=Ganda=lug|li=Limburgish=lim|ln=Lingala=lin|lo=Lao=lao|lt=Lithuanian=lit|lu=Luba-Katanga=lub|" +
            "lv=Latvian=lav|mg=Malagasy=mlg|mh=Marshallese=mah|mi=Māori=mri|mk=Macedonian=mkd|ml=Malayalam=mal|" +
            "mn=Mongolian=mon|mo=Moldavian=mol|mr=Marathi=mar|ms=Malay=msa|mt=Maltese=mlt|my=Burmese=mya|" +
            "na=Nauru=nau|nb=Norwegian Bokmål=nob|nd=North Ndebele=nde|ne=Nepali=nep|ng=Ndonga=ndo|nl=Dutch=nld|" +
            "nn=Norwegian Nynorsk=nno|no=Norwegian=nor|nr=South Ndebele=nbl|nv=Navajo=nav|ny=Nyanja=nya|oc=Occitan=oci|" +
            "oj=Ojibwa=oji|om=Oromo=orm|or=Odia=ori|os=Ossetic=oss|pa=Punjabi=pan|pi=Pali=pli|" +
            "pl=Polish=pol|ps=Pashto=pus|pt=Portuguese=por|qu=Quechua=que|rm=Romansh=roh|rn=Rundi=run|" +
            "ro=Romanian=ron|ru=Russian=rus|rw=Kinyarwanda=kin|sa=Sanskrit=san|sc=Sardinian=srd|sd=Sindhi=snd|" +
            "se=Northern Sami=sme|sg=Sango=sag|si=Sinhala=sin|sk=Slovak=slk|sl=Slovenian=slv|sm=Samoan=smo|" +
            "sn=Shona=sna|so=Somali=som|sq=Albanian=sqi|sr=Serbian=srp|ss=Swati=ssw|st=Southern Sotho=sot|" +
            "su=Sundanese=sun|sv=Swedish=swe|sw=Swahili=swa|ta=Tamil=tam|te=Telugu=tel|tg=Tajik=tgk|" +
            "th=Thai=tha|ti=Tigrinya=tir|tk=Turkmen=tuk|tl=Tagalog=tgl|tn=Tswana=tsn|to=Tongan=ton|" +
            "tr=Turkish=tur|ts=Tsonga=tso|tt=Tatar=tat|tw=Twi=twi|ty=Tahitian=tah|ug=Uyghur=uig|" +
            "uk=Ukrainian=ukr|ur=Urdu=urd|uz=Uzbek=uzb|ve=Venda=ven|vi=Vietnamese=vie|vo=Volapük=vol|" +
            "wa=Walloon=wln|wo=Wolof=wol|xh=Xhosa=xho|yi=Yiddish=yid|yo=Yoruba=yor|za=Zhuang=zha|" +
            "zh=Chinese=zho|zu=Zulu=zul|"
}
