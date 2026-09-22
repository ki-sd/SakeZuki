"use client";

// 원격 이미지 요청 실패는 렌더링 뒤 브라우저에서 알 수 있어 onError와 상태가 필요하다.
import Image from "next/image";
import {useState} from "react";

interface SakeImageProps{
    src:string | null;
    alt:string;
}

export default function SakeImage({src,alt}:SakeImageProps){
    // DB에 URL이 없거나 실제 이미지 로딩이 실패한 경우 모두 같은 로컬 이미지를 보여준다.
    const [imageSrc,setImageSrc]=useState(src || "/images/sake-placeholder.png");

    return (
        <Image src={imageSrc}
               alt={alt}
               fill
               unoptimized
               onError={()=>setImageSrc("/images/sake-placeholder.png")}
               className={"object-contain"}/>
    );
}