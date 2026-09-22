"use client";

import Image from "next/image";
import {useState} from "react";

interface SakeImageProps{
    src:string | null;
    alt:string;
}

export default function SakeImage({src,alt}:SakeImageProps){
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